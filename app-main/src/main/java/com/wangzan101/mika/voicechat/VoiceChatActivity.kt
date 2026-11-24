package com.wangzan101.mika.voicechat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 语音对话页:豆包实时语音,边说边答,可以随时插话打断。
 * 它和聊天里的助手是两条独立的线:这里说的话不进聊天记录,也不会替你操作手机。
 */
class VoiceChatActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            beginVoiceChat()
        } else {
            Toast.makeText(this@VoiceChatActivity, "需要麦克风权限才能语音对话", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private var controller: VoiceChatController? = null

    /** 配完回来直接接通,不用再点一次。 */
    private var startWhenConfigured = false
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && startWhenConfigured) {
            startWhenConfigured = false
            buildAndStart()
        } else if (result.resultCode != RESULT_OK) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildAndStart()
    }

    private fun buildAndStart() {
        val store = (application as MikaApp).calls.voiceProfiles
        val profile = store.get(CallVoiceProfile.DEFAULT_ID)
        val credentials = profile?.let { store.credentials(it.id) }
        if (profile == null || credentials == null) {
            Toast.makeText(this, "先填一下豆包实时语音的凭据", Toast.LENGTH_SHORT).show()
            startWhenConfigured = true
            settingsLauncher.launch(VoiceSettingsActivity.intent(this))
            return
        }
        controller = VoiceChatController(this, profile, credentials)

        val permsOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (permsOk) beginVoiceChat() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun beginVoiceChat() {
        val voice = controller ?: return
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFEDEFF4)) {
                    VoiceChatScreen(voice, onHangup = { hangUp() })
                }
            }
        }
        lifecycleScope.launch { voice.start() }
    }

    private fun hangUp() {
        lifecycleScope.launch {
            controller?.stop()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val voice = controller ?: return
        controller = null
        lifecycleScope.launch { voice.stop() }
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, VoiceChatActivity::class.java)
    }
}

@Composable
private fun VoiceChatScreen(
    controller: VoiceChatController,
    onHangup: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val muted by controller.muted.collectAsState()
    val speaker by controller.speaker.collectAsState()

    // 出错了:停一下退回聊天
    LaunchedEffect(state.phase) {
        if (state.phase == VoiceChatPhase.ENDED && state.endReason != VoiceChatEnd.USER_HUNG_UP) {
            delay(2_500)
            onHangup()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEDEFF4))) {
        // 一片很淡的浅蓝底光,让中间的白球像浮着
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x334C8BF5), Color(0x004C8BF5)),
                    center = Offset(size.width * 0.5f, size.height * 0.44f),
                    radius = size.minDimension * 0.62f,
                ),
                radius = size.minDimension * 0.62f,
                center = Offset(size.width * 0.5f, size.height * 0.44f),
            )
        }

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            CallHeader()

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PearlBall(
                        speaking = state.aiSpeaking,
                        listening = state.phase == VoiceChatPhase.LISTENING && !muted,
                        level = 0f,
                    )
                    Spacer(modifier = Modifier.height(56.dp))
                    if (state.partial.isNotBlank()) {
                        Caption(text = state.partial, color = Color(0xFF1F2937), weight = FontWeight.Medium)
                    } else if (state.lastAssistantReply.isNotBlank()) {
                        Caption(text = state.lastAssistantReply, color = Color(0xFF374151), weight = FontWeight.Normal)
                    }
                    state.errorDetail?.let {
                        Spacer(modifier = Modifier.height(16.dp))
                        Caption(text = it, color = Color(0xFFDC2626), weight = FontWeight.Normal)
                    }
                }
            }

            Text(
                text = statusLine(state, muted),
                color = Color(0xFF6B7280),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                CallButton(
                    label = if (muted) "取消静音" else "静音",
                    background = if (muted) Color(0xFF1F2937) else Color.White,
                    tint = if (muted) Color.White else Color(0xFF374151),
                    icon = if (muted) MikaIcons.Sound else MikaIcons.Mute,
                    onClick = { controller.setMuted(!muted) },
                )
                CallButton(
                    label = "挂断",
                    background = Color(0xFFEF4444),
                    tint = Color.White,
                    icon = MikaIcons.HandsetDown,
                    size = 76.dp,
                    onClick = onHangup,
                )
                SpeakerSegment(
                    speaker = speaker,
                    onSelect = { controller.setSpeaker(it) },
                )
            }
        }
    }
}

/** 顶上一行:绿点 + 通话时长 + 名字。 */
@Composable
private fun CallHeader() {
    var seconds by remember { mutableLongStateOf(0L) }
    val startAt = remember { SystemClock.elapsedRealtime() }
    LaunchedEffect(Unit) {
        while (true) {
            seconds = (SystemClock.elapsedRealtime() - startAt) / 1000
            delay(1000)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF22C55E)))
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "%02d:%02d".format(seconds / 60, seconds % 60),
            color = Color(0xFF374151),
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = "如意 AI",
            color = Color(0xFF1F2937),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 中间那颗珍珠:听你说时随音量鼓,读的时候一呼一吸。 */
@Composable
private fun PearlBall(speaking: Boolean, listening: Boolean, level: Float) {
    val breathe = rememberInfiniteTransition(label = "pearl")
    val pulse by breathe.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pearl-pulse",
    )
    val scale = when {
        speaking -> pulse
        listening -> (1f + level.coerceIn(0f, 1f) * 0.45f).coerceIn(1f, 1.45f)
        else -> 1f
    }

    Box(contentAlignment = Alignment.Center) {
        // 外圈光晕
        Box(
            modifier = Modifier
                .scale(scale * 1.18f)
                .size(240.dp)
                .blur(38.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            if (speaking) Color(0x664C8BF5) else Color(0x5534D399),
                            Color(0x00FFFFFF),
                        ),
                    ),
                ),
        )
        // 珍珠本体:上方高光、底部一点暖白
        Box(
            modifier = Modifier
                .scale(scale)
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFFFFFF),
                            Color(0xFFF3F6FC),
                            Color(0xFFE2E9F5),
                        ),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    ),
                ),
        )
        // 表面缓慢转动的虹彩
        Canvas(modifier = Modifier.scale(scale).size(200.dp)) {
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0x00FFFFFF), Color(0x33C7D9FF), Color(0x0000FFFF),
                        Color(0x33FFE7C7), Color(0x00FFFFFF),
                    ),
                    center = center,
                ),
                radius = size.minDimension / 2,
                center = center,
                alpha = 0.9f,
            )
        }
        // 里层一道柔光,让它看着是实心的珠子
        Box(
            modifier = Modifier
                .scale(scale)
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x00FFFFFF), Color(0x14000000)),
                        center = Offset(0.5f, 0.62f),
                        radius = 420f,
                    ),
                ),
        )
    }
}

@Composable
private fun Caption(text: String, color: Color, weight: FontWeight) {
    Text(
        text = text,
        color = color,
        fontSize = 16.sp,
        fontWeight = weight,
        textAlign = TextAlign.Center,
        lineHeight = 24.sp,
        modifier = Modifier.padding(horizontal = 40.dp),
    )
}

@Composable
private fun CallButton(
    label: String,
    background: Color,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp = 64.dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(background)
                .size(size)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(size * 0.44f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, color = Color(0xFF6B7280), fontSize = 12.sp)
    }
}

private fun statusLine(state: VoiceChatState, muted: Boolean): String = when (state.phase) {
    VoiceChatPhase.STARTING -> "正在接通…"
    VoiceChatPhase.LISTENING -> when {
        muted -> "已静音"
        state.partial.isNotBlank() -> "在听…"
        else -> "在听···"
    }
    VoiceChatPhase.SPEAKING -> "正在说···（开口可以打断）"
    VoiceChatPhase.ENDED -> if (state.endReason == VoiceChatEnd.USER_HUNG_UP) "已挂断" else "语音对话没法继续"
}
