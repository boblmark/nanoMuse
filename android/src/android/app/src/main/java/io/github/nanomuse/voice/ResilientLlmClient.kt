package io.github.nanomuse.voice

// nanoMuse: Android 端弹性 LLM 客户端
// 对应后端 resilient_llm.py：熔断 + 自动降级
// 对接上游 com.openminis.app.data.model.ProviderInstance

import android.util.Log
import com.openminis.app.data.model.ProviderInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ResilientLlmClient — 带熔断和自动降级的 LLM 客户端
 *
 * 降级链：按传入的 provider 列表顺序尝试（调用方从上游 ProviderRepository 取 enabledInstances）
 * 熔断：连续失败 3 次后熔断，5 分钟冷却
 * 额度检测：识别 429/quota 等错误
 */
class ResilientLlmClient(
    /** 根据 provider id 取 API key（调用方对接上游凭据仓库） */
    private val apiKeyProvider: suspend (ProviderInstance) -> String?,
) {
    companion object {
        private const val TAG = "ResilientLlm"
        private const val CIRCUIT_BREAKER_THRESHOLD = 3
        private const val COOLDOWN_MS = 5 * 60 * 1000L  // 5 分钟
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // 运行时健康状态（不持久化）
    private val healthMap = mutableMapOf<String, ProviderHealth>()

    data class ChatResult(
        val success: Boolean,
        val content: String?,
        val providerId: String?,
        val providerName: String?,
        val fallbackUsed: Boolean,
        val error: String? = null,
    )

    private fun getHealth(providerId: String): ProviderHealth {
        return healthMap.getOrPut(providerId) { ProviderHealth(providerId) }
    }

    private fun isQuotaError(errorMsg: String): Boolean {
        val lower = errorMsg.lowercase()
        return lower.contains("429") ||
                lower.contains("quota") ||
                lower.contains("rate_limit") ||
                lower.contains("rate limit") ||
                lower.contains("insufficient") ||
                lower.contains("exceeded")
    }

    private fun isCircuitOpen(health: ProviderHealth): Boolean {
        if (health.consecutiveFailures < CIRCUIT_BREAKER_THRESHOLD) return false
        val elapsed = System.currentTimeMillis() - health.lastFailureTime
        if (elapsed > COOLDOWN_MS) {
            // 冷却结束，半开：允许一次试探
            health.consecutiveFailures = 0
            return false
        }
        return true
    }

    private fun recordSuccess(providerId: String) {
        val h = getHealth(providerId)
        h.consecutiveFailures = 0
        h.lastSuccessTime = System.currentTimeMillis()
    }

    private fun recordFailure(providerId: String, error: String) {
        val h = getHealth(providerId)
        h.consecutiveFailures++
        h.lastFailureTime = System.currentTimeMillis()
        h.lastError = error
        if (h.consecutiveFailures >= CIRCUIT_BREAKER_THRESHOLD) {
            Log.w(TAG, "Circuit opened for $providerId after ${h.consecutiveFailures} failures")
        }
    }

    /**
     * 按顺序尝试 providers，自动降级。
     * @param providers 上游的 ProviderInstance 列表（已按用户配置的顺序排好）
     * @param model 要用的模型名
     * @param messages OpenAI 格式的 messages
     */
    suspend fun chatWithFallback(
        providers: List<ProviderInstance>,
        model: String,
        messages: List<Map<String, String>>,
    ): ChatResult = withContext(Dispatchers.IO) {
        val enabled = providers.filter { it.isEnabled }
        if (enabled.isEmpty()) {
            return@withContext ChatResult(false, null, null, null, false, "No enabled providers")
        }

        var lastError: String? = null
        var tried = 0

        for (provider in enabled) {
            val health = getHealth(provider.id)
            if (isCircuitOpen(health)) {
                Log.d(TAG, "Skipping ${provider.id} (circuit open)")
                continue
            }

            val apiKey = try {
                apiKeyProvider(provider)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get API key for ${provider.id}", e)
                null
            }
            if (apiKey.isNullOrBlank()) {
                Log.d(TAG, "Skipping ${provider.id} (no API key)")
                continue
            }

            tried++
            val baseUrl = provider.customBaseURL?.trimEnd('/')
                ?: "https://api.openai.com"

            try {
                val content = callOpenAI(baseUrl, apiKey, model, messages)
                recordSuccess(provider.id)
                return@withContext ChatResult(
                    success = true,
                    content = content,
                    providerId = provider.id,
                    providerName = provider.label,
                    fallbackUsed = tried > 1,
                )
            } catch (e: Exception) {
                val msg = e.message ?: "unknown error"
                lastError = msg
                recordFailure(provider.id, msg)
                Log.w(TAG, "Provider ${provider.id} failed: $msg")

                // 额度错误立即尝试下一个，不等熔断
                if (isQuotaError(msg)) {
                    Log.i(TAG, "Quota error on ${provider.id}, failing over immediately")
                    continue
                }
                // 其他错误也继续尝试下一个
            }
        }

        ChatResult(
            success = false,
            content = null,
            providerId = null,
            providerName = null,
            fallbackUsed = tried > 1,
            error = lastError ?: "All providers failed or unavailable",
        )
    }

    @Throws(Exception::class)
    private fun callOpenAI(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Map<String, String>>,
    ): String {
        val jsonMessages = JSONArray()
        for (m in messages) {
            jsonMessages.put(JSONObject().apply {
                put("role", m["role"] ?: "user")
                put("content", m["content"] ?: "")
            })
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", jsonMessages)
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: $responseBody")
            }
            val json = JSONObject(responseBody)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }
}

/** 单个 provider 的运行时健康状态 */
private data class ProviderHealth(
    val providerId: String,
    var consecutiveFailures: Int = 0,
    var lastFailureTime: Long = 0,
    var lastSuccessTime: Long = 0,
    var lastError: String? = null,
)
