package com.wangzan101.mika.voicechat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 全局语音通话浮窗状态管理器
 */
class VoiceChatFloatingState(
    val context: Context,
    val scope: CoroutineScope,
) {
    /** 应用外浮窗(通话中、如意 AI 不在前台时由无障碍浮层显示)。 */
    internal val outside = VoiceCallOverlayController(
        (context.applicationContext as MikaApp).foreground,
    )

    init {
        // 前后台切换必须在这里直接订阅,不能靠界面里 collectAsStateWithLifecycle:
        // 那个只在 Activity 处于 STARTED 以上时才收集,一进后台就停,恰好收不到「进入后台」这一跳,
        // 应用外胶囊永远建不出来。
        scope.launch {
            (context.applicationContext as MikaApp).foreground.visible.collect { syncOutside() }
        }
    }

    var controller by mutableStateOf<VoiceChatController?>(null)
        private set

    var isActive by mutableStateOf(false)
        private set

    var isMinimized by mutableStateOf(false)

    /** 全屏数字人态;仅在通话中存在。与 isMinimized 互斥。 */
    var isFullscreen by mutableStateOf(false)
        private set

    var callStartRealtime by mutableLongStateOf(0L)
        private set

    /**
     * 应用外通话浮窗此刻真的显示着;应用内不再画通话胶囊,免得两个通话控件同时出现。
     * 只给非 Compose 逻辑用;界面里必须用 `outside.showing.collectAsState()` 订阅,
     * 直接读这个值不会被 Compose 观察。
     */
    val outsideShowing: Boolean get() = outside.showing.value

    fun startCall(
        onRequestPermission: () -> Unit,
        onOpenSettings: () -> Unit,
    ) {
        val store = (context.applicationContext as MikaApp).calls.voiceProfiles
        val profile = store.get(CallVoiceProfile.DEFAULT_ID)
        val credentials = profile?.let { store.credentials(it.id) }
        if (profile == null || credentials == null) {
            Toast.makeText(context, "先配置豆包实时语音凭据", Toast.LENGTH_SHORT).show()
            onOpenSettings()
            return
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            onRequestPermission()
            return
        }

        // 已经有通话则恢复展开
        if (isActive && controller != null) {
            isMinimized = false
            return
        }

        val newController = VoiceChatController(context, profile, credentials)
        controller = newController
        isActive = true
        isMinimized = false
        callStartRealtime = SystemClock.elapsedRealtime()
        // 先挂前台服务再开采集:没有它,切到别的 App 时系统会立刻掐断麦克风
        VoiceChatService.start(context)

        scope.launch {
            newController.start()
        }
        syncOutside()
    }

    fun hangUp() {
        val ctrl = controller
        controller = null
        isActive = false
        isMinimized = false
        isFullscreen = false
        VoiceChatService.stop(context)
        scope.launch {
            ctrl?.stop()
        }
        outside.close()
    }

    fun toggleMinimize() {
        isMinimized = !isMinimized
        syncOutside()
    }

    /** 收起/展开通话,同步决定应用外浮窗是否需要接手。 */
    fun minimize(minimized: Boolean) {
        isMinimized = minimized
        isFullscreen = false
        syncOutside()
    }

    /** 展开全屏数字人;全屏与贴边胶囊互斥。 */
    fun openFullscreen() {
        isFullscreen = true
        isMinimized = false
        syncOutside()
    }

    /** 退出全屏,回到普通面板。 */
    fun closeFullscreen() {
        isFullscreen = false
    }

    /** 按当前通话状态和前后台重新决定应用外浮窗显不显示。 */
    internal fun syncOutside() {
        outside.sync(this)
    }
}

/**
 * 跨页面全局语音通话浮窗组件 (Chat 与 Remote 均可见)
 *
 * 包含三态：
 * 1. 未通话态（Idle）：独立可拖拽的绿色电话悬浮球，点击立刻呼出语音通话；
 * 2. 通话贴边胶囊态（Minimized）：拖拽吸边、动态波形呼吸绿点、通话时长计时；
 * 3. 通话面板展开态（Expanded）：毛玻璃面板，珍珠动效、文字预览、静音、免提与挂断。
 */
@Composable
fun VoiceChatFloatingOverlay(
    floatingState: VoiceChatFloatingState,
    onStartCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = floatingState.controller
    val inCall = floatingState.isActive && controller != null

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val marginPx = with(density) { 16.dp.toPx() }
        val topBoundPx = with(density) { 70.dp.toPx() }
        val bottomBoundPx = maxH - with(density) { 130.dp.toPx() }

        // 记录悬浮位置（松手自动微吸边，在三态间平滑继承位置）
        // 默认落在左侧、屏幕高度约 62% 处:避开顶栏与底部输入区,拇指够得到
        var offsetX by remember { mutableFloatStateOf(marginPx) }
        var offsetY by remember { mutableFloatStateOf((maxH * 0.62f).coerceIn(topBoundPx, bottomBoundPx)) }

        if (!inCall) {
            // ============ 0. 未通话态：常驻独立绿色电话悬浮球 ============
            val ballSizePx = with(density) { 52.dp.toPx() }

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(marginPx, maxW - ballSizePx - marginPx)
                                offsetY = (offsetY + dragAmount.y).coerceIn(topBoundPx, bottomBoundPx)
                            },
                            onDragEnd = {
                                val midX = maxW / 2
                                offsetX = if (offsetX + ballSizePx / 2 < midX) marginPx else maxW - ballSizePx - marginPx
                            },
                        )
                    }
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981))
                    .border(1.5.dp, Color(0x66FFFFFF), CircleShape)
                    .size(52.dp)
                    .clickable { onStartCall() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MikaIcons.Handset,
                    contentDescription = "随时发起语音通话",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            return@BoxWithConstraints
        }

        // ============ 通话中状态分流 ============
        val state by controller.state.collectAsState()
        val muted by controller.muted.collectAsState()
        val speaker by controller.speaker.collectAsState()

        // 通话结束自动收起
        LaunchedEffect(state.phase) {
            if (state.phase == VoiceChatPhase.ENDED) {
                delay(1500)
                floatingState.hangUp()
            }
        }

        // 通话时长
        var elapsedSeconds by remember { mutableLongStateOf(0L) }
        LaunchedEffect(floatingState.callStartRealtime) {
            while (floatingState.isActive) {
                elapsedSeconds = (SystemClock.elapsedRealtime() - floatingState.callStartRealtime) / 1000
                delay(1000)
            }
        }

        // 同一个通话只允许一套控件:应用外已显示(即不在前台)时,应用内不再画。
        // 应用外那侧的显示条件本身就限定为「不在前台」,所以前台时这里恒为 false,不会两边都空。
        // 必须用 collectAsState 订阅:showing 是 StateFlow,直接读 .value 不会被 Compose 观察,
        // 切回前台拆掉应用外窗口后不会触发重组,应用内浮窗就再也回不来。
        val outsideShowing by floatingState.outside.showing.collectAsState()
        if (outsideShowing) return@BoxWithConstraints

        if (floatingState.isFullscreen) {
            // ============ 4. 全屏数字人态 ============
            BackHandler { floatingState.closeFullscreen() }
            VoiceAvatarFullscreen(
                state = state,
                muted = muted,
                speaker = speaker,
                elapsedSeconds = elapsedSeconds,
                onMute = { controller.setMuted(!muted) },
                onSpeaker = { controller.setSpeaker(it) },
                onHangUp = { floatingState.hangUp() },
                onExitFullscreen = { floatingState.closeFullscreen() },
            )
            return@BoxWithConstraints
        }

        if (floatingState.isMinimized) {
            // ============ 1. 贴边可拖拽胶囊态 ============
            val capsuleWidthPx = with(density) { 116.dp.toPx() }
            val capsuleHeightPx = with(density) { 40.dp.toPx() }

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(marginPx, maxW - capsuleWidthPx - marginPx)
                                offsetY = (offsetY + dragAmount.y).coerceIn(topBoundPx, bottomBoundPx)
                            },
                            onDragEnd = {
                                val midX = maxW / 2
                                offsetX = if (offsetX + capsuleWidthPx / 2 < midX) marginPx else maxW - capsuleWidthPx - marginPx
                            },
                        )
                    }
                    .shadow(12.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF0181F2E))
                    .border(1.dp, Color(0x334C8BF5), RoundedCornerShape(20.dp))
                    .clickable { floatingState.minimize(false) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CallGreenIndicator(aiSpeaking = state.aiSpeaking)

                    Text(
                        text = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )

                    Icon(
                        imageVector = MikaIcons.Handset,
                        contentDescription = "展开语音通话",
                        tint = if (muted) Color(0xFFEF4444) else Color(0xFF34D399),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        } else {
            // ============ 2. 居中悬浮毛玻璃面板态 ============
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        floatingState.minimize(true)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                        .padding(horizontal = 24.dp)
                        .widthIn(min = 280.dp, max = 340.dp)
                        .shadow(24.dp, RoundedCornerShape(28.dp))
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xF2F5F7FA))
                        .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(28.dp))
                        .padding(20.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E)),
                                )
                                Text(
                                    text = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
                                    color = Color(0xFF4B5563),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "如意 AI 语音",
                                    color = Color(0xFF111827),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x1A000000))
                                    .clickable { floatingState.openFullscreen() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MikaIcons.Expand,
                                    contentDescription = "展开全屏数字人",
                                    tint = Color(0xFF374151),
                                    modifier = Modifier.size(16.dp),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x1A000000))
                                    .clickable { floatingState.minimize(true) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MikaIcons.Back,
                                    contentDescription = "收起为浮窗",
                                    tint = Color(0xFF374151),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        MiniPearlBall(
                            speaking = state.aiSpeaking,
                            listening = state.phase == VoiceChatPhase.LISTENING && !muted,
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        val previewText = when {
                            state.partial.isNotBlank() -> state.partial
                            state.lastAssistantReply.isNotBlank() -> state.lastAssistantReply
                            else -> ""
                        }

                        if (previewText.isNotBlank()) {
                            Text(
                                text = previewText,
                                color = Color(0xFF1F2937),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }

                        state.errorDetail?.let { err ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = err,
                                color = Color(0xFFDC2626),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = floatingStatusLine(state, muted),
                            color = Color(0xFF6B7280),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FloatingCallButton(
                                label = if (muted) "取消静音" else "静音",
                                background = if (muted) Color(0xFF1F2937) else Color.White,
                                tint = if (muted) Color.White else Color(0xFF374151),
                                icon = if (muted) MikaIcons.Sound else MikaIcons.Mute,
                                onClick = { controller.setMuted(!muted) },
                            )
                            FloatingCallButton(
                                label = "挂断",
                                background = Color(0xFFEF4444),
                                tint = Color.White,
                                icon = MikaIcons.HandsetDown,
                                size = 62.dp,
                                onClick = { floatingState.hangUp() },
                            )
                            SpeakerSegment(
                                speaker = speaker,
                                onSelect = { controller.setSpeaker(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 胶囊左侧的动态呼吸绿点 */
@Composable
internal fun CallGreenIndicator(aiSpeaking: Boolean) {
    val transition = rememberInfiniteTransition(label = "greenPulse")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = if (aiSpeaking) 1.35f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (aiSpeaking) 450 else 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(Color(0x6622C55E)),
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(Color(0xFF22C55E)),
        )
    }
}

/** 浮窗版呼吸灯:浮窗是浅色毛玻璃底,彩虹在浅色上会糊成一片脏色,所以只用品牌绿单色。 */
@Composable
private fun MiniPearlBall(speaking: Boolean, listening: Boolean) {
    val spirit = remember { Animatable(0f) }
    val speed by animateFloatAsState(
        targetValue = if (speaking) 1.9f else if (listening) 1.2f else 0.8f,
        animationSpec = tween(600),
        label = "miniSpiritSpeed",
    )
    LaunchedEffect(speed) {
        while (true) {
            withFrameNanos { }
            spirit.snapTo((spirit.value + 0.004f * speed) % 1f)
        }
    }
    val t = spirit.value
    val breath = (sin(t * 2 * PI).toFloat() + 1f) / 2f
    val glow = if (speaking) 0.85f else 0.6f

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val base = size.minDimension / 2f

            // 外圈:品牌绿光晕,随呼吸胀缩
            drawCircle(
                color = Color(0xFF22C55E),
                radius = base * (0.8f + 0.08f * breath),
                center = center,
                alpha = (0.30f + 0.18f * breath) * glow,
            )
            // 内圈:深一点的绿,叠出层次而不是换色
            drawCircle(
                color = Color(0xFF10B981),
                radius = base * (0.62f + 0.06f * breath),
                center = center,
                alpha = (0.26f + 0.16f * breath) * glow,
            )
            // 灯芯:中心散开的白光,呼吸时最亮
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.85f * (0.35f + 0.65f * breath)), Color(0x00FFFFFF)),
                    center = center,
                    radius = base * 0.62f,
                ),
                radius = base * 0.66f,
                center = center,
            )
        }
    }
}

/**
 * 听筒 / 外放两段式选择:整体尺寸与静音按钮一致(50dp 圆 + 下方标签)。
 * 圆形一半放听筒、一半放外放,当前生效的那半用绿色高亮,下方文字跟着显示当前项。
 */
@Composable
internal fun SpeakerSegment(
    speaker: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = modifier
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .size(50.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeakerSegmentHalf(
                label = "听筒",
                icon = MikaIcons.Earpiece,
                selected = !speaker,
                onClick = { onSelect(false) },
            )
            SpeakerSegmentHalf(
                label = "外放",
                icon = MikaIcons.Speaker,
                selected = speaker,
                onClick = { onSelect(true) },
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = if (speaker) "外放" else "听筒", color = Color(0xFF6B7280), fontSize = 11.sp)
    }
}

/** 圆的一半:半圆点击区,选中时铺满绿色。必须在 [androidx.compose.foundation.layout.RowScope] 里用。 */
@Composable
private fun RowScope.SpeakerSegmentHalf(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) Color.White else Color(0xFF6B7280)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .weight(1f)
            .background(if (selected) Color(0xFF10B981) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun FloatingCallButton(
    label: String,
    background: Color,
    tint: Color,
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp = 50.dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .shadow(4.dp, CircleShape)
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
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = label, color = Color(0xFF6B7280), fontSize = 11.sp)
    }
}

private fun floatingStatusLine(state: VoiceChatState, muted: Boolean): String = when (state.phase) {
    VoiceChatPhase.STARTING -> "正在接通…"
    VoiceChatPhase.LISTENING -> when {
        muted -> "已静音"
        state.partial.isNotBlank() -> "在听…"
        else -> "随时对我说…"
    }
    VoiceChatPhase.SPEAKING -> "正在回答…"
    VoiceChatPhase.ENDED -> when (state.endReason) {
        VoiceChatEnd.USER_HUNG_UP -> "通话已结束"
        VoiceChatEnd.PERMISSION_DENIED -> "麦克风权限被拒"
        VoiceChatEnd.NOT_CONFIGURED -> "尚未配置凭据"
        VoiceChatEnd.CONNECT_FAILED -> "连接中断"
        VoiceChatEnd.AUDIO_FAILED -> "音频打开失败"
        null -> "通话已结束"
    }
}

/** 全屏数字人:占位形象 + 会话字幕 + 通话控件。 */
@Composable
private fun VoiceAvatarFullscreen(
    state: VoiceChatState,
    muted: Boolean,
    speaker: Boolean,
    elapsedSeconds: Long,
    onMute: () -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onHangUp: () -> Unit,
    onExitFullscreen: () -> Unit,
) {
    val speaking = state.phase == VoiceChatPhase.SPEAKING
    val listening = state.phase == VoiceChatPhase.LISTENING

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF6F9FF), Color(0xFFEAF1FC), Color(0xFFF4F7FD)),
                ),
            ),
    ) {
        // 顶部导航
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallGreenIndicator(aiSpeaking = speaking)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
                fontSize = 14.sp,
                color = Color(0xFF6B7280),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "如意 AI 语音",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF111827),
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0x14000000))
                    .clickable(onClick = onExitFullscreen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MikaIcons.Back,
                    contentDescription = "退出全屏",
                    tint = Color(0xFF374151),
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.28f))
            AvatarPlaceholder(speaking = speaking, listening = listening)
            Spacer(Modifier.height(28.dp))
            Text(
                text = floatingStatusLine(state, muted),
                fontSize = 13.sp,
                color = Color(0xFF9AA3B2),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.partial.ifBlank { state.lastAssistantReply }.ifBlank { "随时对我说…" },
                fontSize = 17.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1F2937),
                textAlign = TextAlign.Center,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.weight(0.42f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingCallButton(
                    label = if (muted) "取消静音" else "静音",
                    background = if (muted) Color(0xFF1F2937) else Color.White,
                    tint = if (muted) Color.White else Color(0xFF374151),
                    icon = if (muted) MikaIcons.Sound else MikaIcons.Mute,
                    onClick = onMute,
                )
                FloatingCallButton(
                    label = "挂断",
                    background = Color(0xFFEF4444),
                    tint = Color.White,
                    icon = MikaIcons.HandsetDown,
                    size = 62.dp,
                    onClick = onHangUp,
                )
                SpeakerSegment(
                    speaker = speaker,
                    onSelect = onSpeaker,
                )
            }

            state.errorDetail?.takeIf { it.isNotBlank() }?.let { err ->
                Spacer(Modifier.height(14.dp))
                Text(
                    text = err,
                    fontSize = 12.sp,
                    color = Color(0xFFDC2626),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** 数字人占位形象:品牌绿呼吸灯,明暗随呼吸起伏。 */
@Composable
private fun AvatarPlaceholder(speaking: Boolean, listening: Boolean) {
    val spirit = remember { Animatable(0f) }
    val speed by animateFloatAsState(
        targetValue = if (speaking) 1.9f else if (listening) 1.2f else 0.8f,
        animationSpec = tween(600),
        label = "spiritSpeed",
    )
    LaunchedEffect(speed) {
        // 每帧推进一小段,按当前速率换算成「圈数」
        while (true) {
            withFrameNanos { }
            spirit.snapTo((spirit.value + 0.004f * speed) % 1f)
        }
    }
    val t = spirit.value
    val breath = (sin(t * 2 * PI).toFloat() + 1f) / 2f
    val glow = if (speaking) 1f else 0.66f

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(300.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val base = size.minDimension / 2f

            // 1. 外圈光晕:品牌绿,随呼吸胀缩,叠加出通亮的灯感
            drawCircle(
                color = Color(0xFF22C55E),
                radius = base * (0.58f + 0.14f * breath),
                center = center,
                alpha = (0.34f + 0.26f * breath) * glow,
                blendMode = BlendMode.Plus,
            )

            // 2. 中层:深一点的绿再叠一层,做出通透的层次
            drawCircle(
                color = Color(0xFF10B981),
                radius = base * (0.44f + 0.1f * breath),
                center = center,
                alpha = (0.28f + 0.22f * breath) * glow,
                blendMode = BlendMode.Plus,
            )

            // 3. 灯芯:随呼吸明灭的白光,让人一眼看出它正在「呼吸」
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = 0.92f * (0.3f + 0.7f * breath)),
                        Color(0x00FFFFFF),
                    ),
                    center = center.copy(x = center.x - base * 0.1f, y = center.y - base * 0.14f),
                    radius = base * 0.46f,
                ),
                radius = base * 0.5f,
                center = center,
            )
        }

        // 4. 中间的人像剪影:随呼吸轻微起伏
        Icon(
            imageVector = MikaIcons.Person,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .size(62.dp)
                .scale(1f + 0.05f * breath),
        )

        if (speaking) {
            AvatarWaveform(modifier = Modifier.align(Alignment.BottomCenter).offset(y = 34.dp))
        } else if (listening) {
            Text(
                text = "在听…",
                fontSize = 12.sp,
                color = Color(0xFF9AA3B2),
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = 34.dp),
            )
        }
    }
}

/** 波纹统一用品牌绿:深浅交替,靠明暗区分层次,不再轮换紫/粉/青。 */
private fun rippleColor(index: Int): Color = when (index % 2) {
    0 -> Color(0xFF22C55E)
    else -> Color(0xFF10B981)
}

/** 说话时的一排声波,同一条绿色渐变。 */
@Composable
private fun AvatarWaveform(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val bars = listOf(300, 480, 380, 540, 340).mapIndexed { index, duration ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(duration), RepeatMode.Reverse),
            label = "bar$index",
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEachIndexed { index, amplitude ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((8 + 20 * amplitude.value).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                rippleColor(index).copy(alpha = 0.95f),
                                rippleColor(index + 1).copy(alpha = 0.55f),
                            ),
                        ),
                    ),
            )
        }
    }
}

