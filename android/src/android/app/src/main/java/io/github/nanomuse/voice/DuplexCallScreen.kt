package io.github.nanomuse.voice

// nanoMuse: 全双工语音通话界面 (Compose)

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * 全双工语音通话界面
 *
 * 状态显示 + 波形动画 + 挂断/静音/免提按钮
 */
@Composable
fun DuplexCallScreen(
    onHangup: () -> Unit = {},
) {
    val context = LocalContext.current
    val callManager = remember { DuplexCallManager(context) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var callState by remember { mutableStateOf(DuplexCallManager.CallState.IDLE) }
    var isMuted by remember { mutableStateOf(false) }
    var isVideoOn by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // nanoMuse: 模型选择（2026-10-12）
    var selectedModel by remember { mutableStateOf(DuplexCallManager.AVAILABLE_MODELS[0]) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    
    // 监听通话状态
    DisposableEffect(callManager) {
        callManager.listener = object : DuplexCallManager.CallListener {
            override fun onStateChanged(state: DuplexCallManager.CallState) {
                callState = state
            }
            override fun onInterrupted() {
                // 被打断，可以显示提示
            }
            override fun onError(error: String) {
                errorMsg = error
            }
        }
        // nanoMuse: 设置初始模型（2026-10-12）
        callManager.selectedModel = selectedModel
        callManager.startCall()
        
        onDispose {
            callManager.endCall()
            callManager.release()
        }
    }
    
    // 状态文本
    val statusText = when (callState) {
        DuplexCallManager.CallState.IDLE -> ""
        DuplexCallManager.CallState.CONNECTING ->
            stringResource(R.string.nm_duplex_connecting)
        DuplexCallManager.CallState.ACTIVE ->
            stringResource(R.string.nm_duplex_active)
        DuplexCallManager.CallState.RECONNECTING ->
            stringResource(R.string.nm_duplex_reconnecting)
        DuplexCallManager.CallState.ENDED ->
            stringResource(R.string.nm_duplex_ended)
    }
    
    // 通话结束自动返回
    LaunchedEffect(callState) {
        if (callState == DuplexCallManager.CallState.ENDED) {
            onHangup()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 头像 / 波形动画
            CallAvatar(isActive = callState == DuplexCallManager.CallState.ACTIVE)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = stringResource(R.string.nm_duplex_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            
            errorMsg?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.nm_duplex_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // nanoMuse: 模型选择下拉（2026-10-12）
            Spacer(modifier = Modifier.height(16.dp))
            Box {
                OutlinedButton(
                    onClick = { modelMenuOpen = true },
                ) {
                    Text(
                        text = selectedModel,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "选择模型",
                    )
                }
                DropdownMenu(
                    expanded = modelMenuOpen,
                    onDismissRequest = { modelMenuOpen = false },
                ) {
                    DuplexCallManager.AVAILABLE_MODELS.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                selectedModel = model
                                callManager.selectedModel = model
                                modelMenuOpen = false
                            },
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // 控制按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 静音
                FilledIconButton(
                    onClick = { isMuted = !isMuted },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isMuted)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Icon(
                        if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = stringResource(
                            if (isMuted) R.string.nm_duplex_unmute
                            else R.string.nm_duplex_mute
                        ),
                    )
                }
                
                // 挂断
                FilledIconButton(
                    onClick = {
                        callManager.endCall()
                        onHangup()
                    },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFFE53935),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = stringResource(R.string.nm_duplex_hangup),
                        modifier = Modifier.size(32.dp),
                    )
                }
                
                // 免提（占位，实际切换 AudioManager.isSpeakerphoneOn）
                FilledIconButton(
                    onClick = { /* TODO: 切换免提 */ },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = stringResource(R.string.nm_duplex_speaker),
                    )
                }

                // 视频开关
                FilledIconButton(
                    onClick = {
                        isVideoOn = !isVideoOn
                        callManager.videoEnabled = isVideoOn
                        if (isVideoOn) {
                            callManager.startVideoCapture(lifecycleOwner)
                        } else {
                            callManager.stopVideoCapture()
                        }
                    },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isVideoOn)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Icon(
                        if (isVideoOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        contentDescription = if (isVideoOn) "关闭视频" else "开启视频",
                    )
                }
            }
        }
    }
}

/**
 * 通话头像：活跃时带呼吸动画
 */
@Composable
private fun CallAvatar(isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "avatar")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    
    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (isActive)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "🎙",
            style = MaterialTheme.typography.displayMedium,
        )
    }
}
