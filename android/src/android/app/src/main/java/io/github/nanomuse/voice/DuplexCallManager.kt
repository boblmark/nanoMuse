package io.github.nanomuse.voice

// nanoMuse: 全双工实时语音通话管理器，对应服务端 duplex_conversation.py

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
// nanoMuse: 视频通话 - CameraX
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [T-android-duplex-call] DuplexCallManager — 全双工实时语音通话管理器
 *
 * 对应服务端 duplex_conversation.py，实现 Muse HatchWsSignalingService 双工模式的
 * Android 客户端：
 * - WebSocket 双向实时音频流（wss://nano.viralloopapp.com）
 * - 支持打断（用户说话时自动停止播放）
 * - AudioFocus 管理（MODE_IN_COMMUNICATION + 回声消除）
 * - 自动重连
 *
 * 协议（服务端 perception_server.py）：
 * - 发送: {"type": "duplex_start"} → 收到 {"type": "duplex_started", "session_id": ...}
 * - 发送: {"type": "duplex_audio", "session_id": ..., "data": base64} → 用户音频上行
 * - 接收: {"type": "duplex_audio", "session_id": ..., "data": base64} → nano 音频下行
 * - 接收: {"type": "duplex_interrupt", "session_id": ...} → 被打断，停止播放
 * - 发送: {"type": "duplex_end", "session_id": ...} → 结束
 */
class DuplexCallManager(
    private val context: Context,
    private val serverUrl: String = "wss://nano.viralloopapp.com",
) {
    companion object {
        private const val TAG = "DuplexCall"
        
        // 音频参数（与服务端 webm_to_pcm16 对齐：16kHz mono PCM16）
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        
        // 重连配置
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2000L
    }
    
    enum class CallState {
        IDLE,           // 空闲
        CONNECTING,     // 连接中
        ACTIVE,         // 通话中
        RECONNECTING,   // 重连中
        ENDED,          // 已结束
    }
    
    interface CallListener {
        fun onStateChanged(state: CallState)
        fun onInterrupted()  // 被打断
        fun onError(error: String)
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private var sessionId: String? = null
    private var callState: CallState = CallState.IDLE
        set(value) {
            field = value
            listener?.onStateChanged(value)
        }
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    // nanoMuse: 视频通话
    private var imageCapture: ImageCapture? = null
    private var videoJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null
    var videoEnabled: Boolean = false
    // nanoMuse: 模型选择（2026-10-12）
    var selectedModel: String = "gemini-2.0-flash-exp"
    companion object {
        val AVAILABLE_MODELS = listOf(
            "gemini-2.0-flash-exp",
            "gemini-2.5-flash-preview-04-17",
            "gemini-1.5-flash",
        )
    }
    
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    var listener: CallListener? = null
    private var reconnectAttempts = 0
    
    /**
     * 开始通话
     */
    fun startCall() {
        if (callState == CallState.ACTIVE || callState == CallState.CONNECTING) {
            Log.w(TAG, "通话已在进行中")
            return
        }
        
        callState = CallState.CONNECTING
        scope.launch {
            try {
                // 1. 申请音频焦点（通话模式）
                requestAudioFocus()
                
                // 2. 连接 WebSocket
                connectWebSocket()
            } catch (e: Exception) {
                Log.e(TAG, "启动通话失败", e)
                callState = CallState.IDLE
                listener?.onError(e.message ?: "未知错误")
            }
        }
    }
    
    /**
     * 结束通话
     */
    fun endCall() {
        scope.launch {
            sessionId?.let { sid ->
                try {
                    webSocket?.send(
                        JSONObject().apply {
                            put("type", "duplex_end")
                            put("session_id", sid)
                        }.toString()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "发送结束信令失败", e)
                }
            }
            cleanup()
            callState = CallState.ENDED
        }
    }
    
    private fun connectWebSocket() {
        val request = Request.Builder()
            .url(serverUrl)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接")
                reconnectAttempts = 0
                // 发送 duplex_start（nanoMuse: 带模型选择）
                webSocket.send(
                    JSONObject().apply {
                        put("type", "duplex_start")
                        put("model", selectedModel)
                    }.toString()
                )
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 关闭中: $code $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 已关闭: $code $reason")
                if (callState == CallState.ACTIVE) {
                    attemptReconnect()
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 失败", t)
                if (callState == CallState.ACTIVE || callState == CallState.CONNECTING) {
                    attemptReconnect()
                } else {
                    listener?.onError(t.message ?: "连接失败")
                }
            }
        })
    }
    
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "duplex_started" -> {
                    sessionId = json.optString("session_id")
                    Log.i(TAG, "全双工会话已启动: $sessionId")
                    callState = CallState.ACTIVE
                    startAudioCapture()
                    startAudioPlayback()
                }
                "duplex_audio" -> {
                    // nano 的音频下行
                    val data = json.optString("data")
                    if (data.isNotEmpty()) {
                        playAudio(data)
                    }
                }
                "duplex_interrupt" -> {
                    // 被打断，停止播放
                    Log.i(TAG, "收到打断信令，停止播放")
                    stopPlayback()
                    listener?.onInterrupted()
                }
                "duplex_ended" -> {
                    Log.i(TAG, "服务端结束通话")
                    scope.launch { cleanup() }
                    callState = CallState.ENDED
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理消息失败: $text", e)
        }
    }
    
    private fun startAudioCapture() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_IN, ENCODING
        )
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "不支持的音频参数")
            return
        }
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // 通话模式，带回声消除
            SAMPLE_RATE,
            CHANNEL_IN,
            ENCODING,
            minBufferSize * 2
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            return
        }
        
        audioRecord?.startRecording()
        Log.i(TAG, "开始音频采集")
        
        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(minBufferSize)
            // 简单起见，直接发送 PCM（服务端 webm_to_pcm16 也能处理原始 PCM）
            // 实际生产环境应该用 Opus 编码压缩
            while (isActive && callState == CallState.ACTIVE) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // 检测音量，静音时不发送（省带宽）
                    if (!isSilent(buffer, read)) {
                        val base64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        val sid = sessionId ?: continue
                        try {
                            webSocket?.send(
                                JSONObject().apply {
                                    put("type", "duplex_audio")
                                    put("session_id", sid)
                                    put("data", base64)
                                }.toString()
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "发送音频失败", e)
                        }
                    }
                }
                // 约 100ms 一包
                delay(100)
            }
        }
    }

    /**
     * nanoMuse: 启动视频采集（CameraX，每秒 1 帧 JPEG）
     * 对应服务端 duplex_conversation.py 的视频帧处理
     */
    fun startVideoCapture(lifecycleOwner: LifecycleOwner) {
        if (!videoEnabled) {
            Log.w(TAG, "视频未启用，跳过")
            return
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                this.imageCapture = imageCapture

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner, cameraSelector, imageCapture
                )
                Log.i(TAG, "视频采集已启动（前置摄像头）")

                // 每秒抓 1 帧
                videoJob = scope.launch(Dispatchers.IO) {
                    while (isActive && callState == CallState.ACTIVE && videoEnabled) {
                        captureAndSendFrame()
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "视频采集启动失败", e)
                listener?.onError("视频启动失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun captureAndSendFrame() {
        val imageCapture = this.imageCapture ?: return
        val sid = sessionId ?: return
        try {
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val jpegBytes = imageProxyToJpeg(image)
                            if (jpegBytes != null) {
                                val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                                webSocket?.send(
                                    JSONObject().apply {
                                        put("type", "duplex_video")
                                        put("session_id", sid)
                                        put("data", base64)
                                    }.toString()
                                )
                            }
                        } finally {
                            image.close()
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "抓帧失败", exception)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "视频帧发送失败", e)
        }
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        return try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            // ImageProxy 是 YUV，需要转 JPEG
            // 简单方案：用 Bitmap 压缩（实际应做 YUV→RGB 转换）
            // 这里先返回原始数据，服务端处理
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "图像转换失败", e)
            null
        }
    }

    fun stopVideoCapture() {
        videoJob?.cancel()
        videoJob = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "解绑相机失败", e)
        }
        imageCapture = null
        cameraProvider = null
        Log.i(TAG, "视频采集已停止")
    }

    private fun startAudioPlayback() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_OUT, ENCODING
        )
        
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_OUT)
            .setEncoding(ENCODING)
            .build()
        
        audioTrack = AudioTrack(
            attributes,
            format,
            minBufferSize * 2,
            AudioTrack.MODE_STREAM,
            audioManager.generateAudioSessionId()
        )
        
        audioTrack?.play()
        Log.i(TAG, "音频播放已就绪")
    }
    
    private fun playAudio(base64Data: String) {
        try {
            val pcmData = Base64.decode(base64Data, Base64.DEFAULT)
            // 在 IO 线程播放，避免阻塞
            scope.launch(Dispatchers.IO) {
                audioTrack?.write(pcmData, 0, pcmData.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放音频失败", e)
        }
    }
    
    private fun stopPlayback() {
        // 停止当前播放（打断时调用）
        // AudioTrack 没有 pause 后清空的直接方法，这里用 flush
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "停止播放失败", e)
        }
    }
    
    private fun isSilent(buffer: ByteArray, length: Int): Boolean {
        // 简单的静音检测：计算平均振幅
        var sum = 0L
        // PCM16，每 2 字节一个采样
        var i = 0
        while (i < length - 1) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF))
            sum += kotlin.math.abs(sample)
            i += 2
        }
        val avg = sum / (length / 2)
        return avg < 500  // 阈值，可调
    }
    
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            Log.i(TAG, "音频焦点丢失，结束通话")
                            endCall()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            Log.i(TAG, "音频焦点暂时丢失")
                            // 可以暂停采集
                        }
                    }
                }
                .build()
            
            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "音频焦点申请未获批准: $result")
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "音频焦点申请未获批准 (legacy): $result")
            }
        }
        
        // 设置通话模式（启用回声消除）
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false  // 默认用听筒，可切换
    }
    
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }
    
    private fun attemptReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "重连次数超限，结束通话")
            scope.launch { cleanup() }
            callState = CallState.IDLE
            listener?.onError("连接断开，重连失败")
            return
        }
        
        reconnectAttempts++
        callState = CallState.RECONNECTING
        Log.i(TAG, "尝试重连 ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
        
        scope.launch {
            delay(RECONNECT_DELAY_MS * reconnectAttempts)
            if (callState == CallState.RECONNECTING) {
                connectWebSocket()
            }
        }
    }
    
    private suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            captureJob?.cancel()
            captureJob = null
            // nanoMuse: 视频
            videoJob?.cancel()
            videoJob = null
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Log.e(TAG, "解绑相机失败", e)
            }
            imageCapture = null
            cameraProvider = null

            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放 AudioRecord 失败", e)
            }
            audioRecord = null
            
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放 AudioTrack 失败", e)
            }
            audioTrack = null
            
            try {
                webSocket?.close(1000, "通话结束")
            } catch (e: Exception) {
                Log.e(TAG, "关闭 WebSocket 失败", e)
            }
            webSocket = null
            sessionId = null
        }
        
        abandonAudioFocus()
        Log.i(TAG, "通话资源已清理")
    }
    
    /**
     * 释放管理器（Activity onDestroy 时调用）
     */
    fun release() {
        scope.launch { cleanup() }
        scope.cancel()
    }
    
    fun getState(): CallState = callState
    fun getSessionId(): String? = sessionId
}
