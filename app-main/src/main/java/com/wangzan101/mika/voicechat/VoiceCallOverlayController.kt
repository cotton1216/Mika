package com.wangzan101.mika.voicechat

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import com.wangzan101.mika.cap.phone.a11y.OverlayDrag
import com.wangzan101.mika.cap.phone.a11y.OverlayHost
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.capsule.AppForeground
import com.wangzan101.mika.ui.theme.MikaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 应用外的语音通话浮窗:通话进行中且如意 AI 不在前台时,用无障碍浮层在系统桌面上显示
 * 一枚可拖动的通话胶囊;点开就是通话面板(字幕、静音、挂断、免提)。
 *
 * 走无障碍浮层是因为胶囊已经占了这条通道(不需要「显示在其他应用上层」权限);
 * 无障碍服务没开时这里什么都不显示,应用内的浮窗照常工作。
 * 只在主线程用;只由 [VoiceChatFloatingState] 驱动,不自己发起或结束通话。
 */
class VoiceCallOverlayController(
    private val context: Context,
    private val foreground: AppForeground,
) {
    private var window: OverlayWindow? = null
    private var windowOn: OverlayHost? = null
    /** 胶囊所在的边和位置(屏幕像素),展开成面板时沿用同一处,收起再回来不跳。 */
    private var side = Gravity.RIGHT
    private var offset = 0
    private var placedY = 0
    private val collapseBar = Rect(0, 0, 0, 56)

    /** 浮窗此刻真的显示在屏幕上;应用内的浮窗据此让开,避免两个通话控件同时出现。 */
    val showing = MutableStateFlow(false)

    /**
     * 该不该显示:通话进行中,且(如意 AI 不在前台 或 用户把它收成了胶囊)。
     * 在应用内看面板时不需要应用外的对话框。
     */
    fun sync(floating: VoiceChatFloatingState) {
        val wanted = floating.isActive && floating.controller != null &&
            (!foreground.visible.value || floating.isMinimized)
        if (!wanted) {
            close()
            return
        }
        val service = ScreenAccessibility.instance.value ?: run {
            close()
            return
        }
        val overlays = service.overlays()
        if (window != null && windowOn === overlays) {
            syncDragMode(floating.isMinimized)
            place()
            return
        }
        close()
        open(overlays, floating)
    }

    private fun open(overlays: OverlayHost, floating: VoiceChatFloatingState) {
        val area = overlays.area()
        val ball = overlays.open(OverlayPlacement(Gravity.TOP or side, offset, placedY)) {
            VoiceCallOverlayContent(floating)
        } ?: return
        window = ball
        windowOn = overlays
        ball.onResize(::place)
        syncDragMode(floating.isMinimized)
        // 初始位置:沿用上次的边,纵向默认靠在安全区偏上的位置
        if (placedY == 0) placedY = area.insetTop + (area.height * 0.28f).toInt()
        place()
        showing.value = true
    }

    /** 收起成胶囊时整个窗口都能拖;展开面板时只让顶部那条能拖,别处点击照常给按钮。 */
    private fun syncDragMode(minimized: Boolean) {
        window?.draggable(object : OverlayDrag {
            override fun start() = Unit

            override fun move(dx: Float, dy: Float) {
                windowOn?.let { dragTo(it, dx.toInt(), dy.toInt()) }
            }

            override fun end(dx: Float, dy: Float) {
                windowOn?.let { dragTo(it, dx.toInt(), dy.toInt()) }
            }
        }, if (minimized) null else collapseBar)
    }

    private fun dragTo(overlays: OverlayHost, dx: Int, dy: Int) {
        val w = window ?: return
        val area = overlays.area()
        val width = w.width.takeIf { it > 0 } ?: return
        val height = w.height.takeIf { it > 0 } ?: return
        val left = (if (side == Gravity.RIGHT) area.width - offset - width else offset) + dx
        val top = placedY + dy
        side = if (left + width / 2 < area.width / 2) Gravity.LEFT else Gravity.RIGHT
        offset = if (side == Gravity.LEFT) left else area.width - left - width
        offset = offset.coerceIn(0, maxOf(0, area.width - width))
        placedY = top.coerceIn(area.insetTop, maxOf(area.insetTop, area.height - area.insetBottom - height))
        place()
    }

    private fun place() {
        val w = window ?: return
        val area = (windowOn ?: return).area()
        val height = w.height.takeIf { it > 0 } ?: 0
        placedY = placedY.coerceIn(area.insetTop, maxOf(area.insetTop, area.height - area.insetBottom - height))
        w.place(OverlayPlacement(Gravity.TOP or side, offset, placedY))
    }

    /** 通话结束或无障碍服务断开:拆掉窗口。 */
    fun close() {
        showing.value = false
        window?.close()
        window = null
        windowOn = null
    }
}

/**
 * 应用外浮窗的内容:收起时是一枚通话胶囊(呼吸点 + 计时 + 电话),点开是通话面板;
 * 面板点顶部那条可以拖动(浮层窗口只让那条拖动,别处的点击照常给按钮)。
 */
@Composable
private fun VoiceCallOverlayContent(floating: VoiceChatFloatingState) {
    val controller = floating.controller
    if (controller == null) return

    val state by controller.state.collectAsState()
    val muted by controller.muted.collectAsState()
    val speaker by controller.speaker.collectAsState()

    // 通话结束自动收起(VoiceChatFloatingState 里也会收,这里只保证浮窗不留在屏幕上)
    LaunchedEffect(state.phase) {
        if (state.phase == VoiceChatPhase.ENDED) {
            delay(1500)
            floating.hangUp()
        }
    }

    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(floating.callStartRealtime) {
        while (floating.isActive) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - floating.callStartRealtime) / 1000
            delay(1000)
        }
    }
    val clock = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)

    if (floating.isMinimized) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xF0181F2E))
                .clickable { floating.minimize(false) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CallGreenIndicator(aiSpeaking = state.aiSpeaking)
            Text(clock, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Icon(
                imageVector = MikaIcons.Handset,
                contentDescription = "展开语音通话",
                tint = if (muted) Color(0xFFEF4444) else Color(0xFF34D399),
                modifier = Modifier.size(15.dp),
            )
        }
        return
    }

    val preview = when {
        state.partial.isNotBlank() -> state.partial
        state.lastAssistantReply.isNotBlank() -> state.lastAssistantReply
        else -> ""
    }

    Column(
        modifier = Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF2F5F7FA))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                Text(clock, color = Color(0xFF4B5563), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("如意 AI 语音", color = Color(0xFF111827), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0x1A000000))
                    .clickable { floating.minimize(true) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(MikaIcons.Back, "收起为浮窗", tint = Color(0xFF374151), modifier = Modifier.size(15.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = preview.ifBlank { voiceCallOverlayStatus(state, muted) },
            color = if (preview.isBlank()) Color(0xFF6B7280) else Color(0xFF1F2937),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        state.errorDetail?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color(0xFFDC2626), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayCallButton(
                label = if (muted) "开麦" else "静音",
                background = if (muted) Color(0xFF1F2937) else Color.White,
                tint = if (muted) Color.White else Color(0xFF374151),
                icon = if (muted) MikaIcons.Sound else MikaIcons.Mute,
                onClick = { controller.setMuted(!muted) },
            )
            OverlayCallButton(
                label = "挂断",
                background = Color(0xFFEF4444),
                tint = Color.White,
                icon = MikaIcons.HandsetDown,
                size = 56.dp,
                onClick = { floating.hangUp() },
            )
            OverlayCallButton(
                label = if (speaker) "听筒" else "免提",
                background = if (speaker) Color(0xFF1F2937) else Color.White,
                tint = if (speaker) Color.White else Color(0xFF374151),
                icon = if (speaker) MikaIcons.Speaker else MikaIcons.Earpiece,
                onClick = { controller.setSpeaker(!speaker) },
            )
        }
    }
}

@Composable
private fun OverlayCallButton(
    label: String,
    background: Color,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp = 46.dp,
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
            Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.44f))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xFF6B7280), fontSize = 11.sp)
    }
}

private fun voiceCallOverlayStatus(state: VoiceChatState, muted: Boolean): String = when (state.phase) {
    VoiceChatPhase.STARTING -> "正在接通…"
    VoiceChatPhase.LISTENING -> if (muted) "已静音" else "随时对我说…"
    VoiceChatPhase.SPEAKING -> "正在回答…"
    VoiceChatPhase.ENDED -> "通话已结束"
}
