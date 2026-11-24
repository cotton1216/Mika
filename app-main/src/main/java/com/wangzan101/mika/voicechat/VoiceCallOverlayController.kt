package com.wangzan101.mika.voicechat

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
    private val foreground: AppForeground,
) {
    /**
     * 整个通话期只挂一个窗口,胶囊态与面板态共用它:窗口按内容自适应大小,
     * 形态切换只改 [expanded] 让 Compose 重画,不再拆窗重建。
     */
    private var window: OverlayWindow? = null
    /**
     * 当前窗口是不是展开的面板(否则是胶囊)。窗口的尺寸是 WRAP_CONTENT、跟着内容走,
     * 所以形态切换只改这份状态让 Compose 重画,绝不拆除重建窗口——
     * 在胶囊自己的点击回调里移除包含它的窗口,系统会判定为异常窗口操作,新窗口加不上,
     * 表现就是胶囊消失而面板不出现。
     */
    private val expanded = MutableStateFlow(false)
    private var windowOn: OverlayHost? = null
    /** 用户是在应用外把胶囊点开的:回前台时才需要交还应用内。 */
    private var expandedOutside = false
    /** 窗口停在屏幕的哪一边,以及对应的边距和纵向偏移(屏幕像素)。 */
    private var side = Gravity.LEFT
    private var offset = 0
    private var placedY = 0
    private var placed = false
    /** 面板窗口顶部那条可拖动区;宽度在按到时按窗口实际宽度生成。 */
    private val panelDragBar = Rect(0, 0, 0, 56)

    /** 浮窗此刻真的显示在屏幕上;应用内的浮窗据此让开,避免两个通话控件同时出现。 */
    val showing = MutableStateFlow(false)

    /**
     * 该不该显示:通话进行中,且(如意 AI 不在前台 或 用户把它收成了胶囊 或 外面已经展开)。
     * 在外面点开胶囊后 isMinimized 变 false,靠 expandedOutside 记住「外面正在展开」,
     * 这样点开瞬间不会判成「不该显示」而拆窗。
     */
    fun sync(floating: VoiceChatFloatingState) {
        val alive = floating.isActive && floating.controller != null
        if (!floating.isMinimized && window != null) expandedOutside = true
        // 回到前台且外面已展开:应用内面板接管,外面让位
        if (!alive || (foreground.visible.value && expandedOutside)) {
            close()
            return
        }
        val wanted = !foreground.visible.value || floating.isMinimized || expandedOutside
        if (!wanted) {
            close()
            return
        }
        val service = ScreenAccessibility.instance.value ?: run {
            close()
            return
        }
        val overlays = service.overlays()
        if (windowOn !== overlays) close()
        windowOn = overlays
        if (!placed) {
            // 默认落在左侧中下段,拇指够得到;用户拖过之后沿用拖到的位置
            val area = overlays.area()
            side = Gravity.LEFT
            offset = (area.width * 0.04f).toInt()
            placedY = area.insetTop + (area.height * 0.55f).toInt()
            placed = true
        }
        val wantExpanded = !floating.isMinimized
        if (window == null || expanded.value != wantExpanded) create(overlays, floating, wantExpanded)
        window?.let(::place)
        showing.value = true
    }

    /** 建窗口(只在没有时建一次)并把形态切到 [wantExpanded];窗口按内容自适应,换形态不拆窗。 */
    private fun create(overlays: OverlayHost, floating: VoiceChatFloatingState, wantExpanded: Boolean) {
        if (window == null) {
            val w = overlays.open(OverlayPlacement(Gravity.TOP or side, offset, placedY)) {
                val isExpanded by expanded.collectAsState()
                if (isExpanded) VoiceCallPanelContent(floating, onCollapse = { floating.minimize(true) })
                else VoiceCallPillContent(floating)
            }
            if (w == null) {
                expanded.value = false
                showing.value = false
                return
            }
            w.onResize { place(w) }
            window = w
        }
        expanded.value = wantExpanded
        // 可拖动区随形态变:胶囊整窗可拖,面板只让顶部那条拖
        window?.draggable(dragHandler(overlays, window!!), if (wantExpanded) panelDragBar else null)
    }

    /**
     * 拖动回调:OverlayDrag 给的 dx/dy 是「相对按下那一刻」的累计位移,
     * 所以每次都要从按下时记下的锚点重算,不能把位移累加到当前位置上,否则窗口会越拖越远。
     */
    private fun dragHandler(overlays: OverlayHost, w: OverlayWindow) = object : OverlayDrag {
        private var startSide = Gravity.RIGHT
        private var startOffset = 0
        private var startY = 0

        override fun start() {
            startSide = side
            startOffset = offset
            startY = placedY
        }

        override fun move(dx: Float, dy: Float) = moveTo(overlays, w, startSide, startOffset, startY, dx.toInt(), dy.toInt())

        override fun end(dx: Float, dy: Float) = moveTo(overlays, w, startSide, startOffset, startY, dx.toInt(), dy.toInt())
    }

    private fun moveTo(
        overlays: OverlayHost,
        w: OverlayWindow,
        fromSide: Int,
        fromOffset: Int,
        fromY: Int,
        dx: Int,
        dy: Int,
    ) {
        val area = overlays.area()
        val width = w.width.takeIf { it > 0 } ?: return
        val height = w.height.takeIf { it > 0 } ?: return
        val fromLeft = if (fromSide == Gravity.RIGHT) area.width - fromOffset - width else fromOffset
        val left = fromLeft + dx
        val top = fromY + dy
        side = if (left + width / 2 < area.width / 2) Gravity.LEFT else Gravity.RIGHT
        offset = if (side == Gravity.LEFT) left else area.width - left - width
        offset = offset.coerceIn(0, maxOf(0, area.width - width))
        placedY = top.coerceIn(area.insetTop, maxOf(area.insetTop, area.height - area.insetBottom - height))
        place(w)
    }

    private fun place(w: OverlayWindow) {
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
        expanded.value = false
        windowOn = null
        expandedOutside = false
    }
}

/**
 * 应用外胶囊窗口的内容:呼吸点 + 计时 + 电话,点一下展开成面板。
 */
@Composable
private fun VoiceCallPillContent(floating: VoiceChatFloatingState) {
    val controller = floating.controller ?: return
    val state by controller.state.collectAsState()
    val muted by controller.muted.collectAsState()
    val clock = rememberCallClock(floating)

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
}

/**
 * 应用外面板窗口的内容:字幕、静音、挂断、听筒/外放。
 */
@Composable
private fun VoiceCallPanelContent(floating: VoiceChatFloatingState, onCollapse: () -> Unit) {
    val controller = floating.controller ?: return

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

    val clock = rememberCallClock(floating)

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
                    .clickable(onClick = onCollapse),
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
                label = if (muted) "取消静音" else "静音",
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
            SpeakerSegment(
                speaker = speaker,
                onSelect = { controller.setSpeaker(it) },
            )
        }
    }
}

/** 通话计时文字 mm:ss,两个窗口共用。 */
@Composable
private fun rememberCallClock(floating: VoiceChatFloatingState): String {
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(floating.callStartRealtime) {
        while (floating.isActive) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - floating.callStartRealtime) / 1000
            delay(1000)
        }
    }
    return "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
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
