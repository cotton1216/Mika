package com.xxt.zl.ai.ruyiremote.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space

/* ------------------------------------------------------------------ 容器 */

/**
 * 卡片。全工程的「一块内容」都用它，保证圆角、描边、底色一致。
 * 用 1dp 描边而不是阴影：阴影在深色模式下几乎看不见，描边两种模式都成立。
 */
@Composable
fun DshCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(Space.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalRuyiPalette.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(BorderStroke(1.dp, p.hairline), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        content = content,
    )
}

/** 页面顶部：返回 + 标题 + 右侧动作。内容区自己负责滚动。 */
@Composable
fun DshTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /** 返回按钮的无障碍文案；宿主嵌入时传「返回如愿」之类，让语义更明确。 */
    backLabel: String = "返回",
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalRuyiPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) Space.s else Space.page, end = Space.s, top = Space.s, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            // 有副标题时标题块是两行，居中的圆钮会看着偏低。把它上提半行，
            // 让箭头与标题那一行对齐（40dp 圆钮 vs 24dp 行高 → 8~9dp）。
            PressableCircle(
                onClick = onBack,
                label = backLabel,
                modifier = if (subtitle != null) Modifier.offset(y = (-9).dp) else Modifier,
            ) { GlyphBack(tint = p.text) }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) Space.xs else 0.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = p.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/**
 * 圆形点击区，用于图标按钮。40dp 是最小可靠触摸尺寸的一半留白。
 *
 * [label] 必填：图标是手绘 Canvas，没有文字，读屏软件只能靠 contentDescription
 * 识别；不写的话这些按钮对无障碍用户等于不存在（UI 自动化也定位不到）。
 */
@Composable
fun PressableCircle(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 发丝分割线。比 `HorizontalDivider` 更淡，避免把列表切碎。 */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    val p = LocalRuyiPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(p.hairline),
    )
}

/* ------------------------------------------------------------------ 按钮 */

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val p = LocalRuyiPalette.current
    val active = enabled && !loading
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            // 禁用态不用「accent 降透明度」：紫底浅紫字对比度不足，读起来发灰。
            // 改成中性填充 + 三级文字色，一眼能看出「还不能点」而不是「坏了」。
            .background(if (active) p.accent else p.cardPressed)
            .clickable(enabled = active, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = p.accent,
                )
                Spacer(Modifier.width(Space.s))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (active) p.onAccent else p.textTertiary,
            )
        }
    }
}

/** 次级按钮：描边、无底色。用于「断开」「忘记」这类不该抢视线的动作。 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color? = null,
) {
    val p = LocalRuyiPalette.current
    val color = tone ?: p.text
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, if (enabled) p.hairline else p.hairline.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else color.copy(alpha = 0.4f),
            maxLines = 1,
        )
    }
}

/* ------------------------------------------------------------------ 小部件 */

/** 在线状态点：外圈半透明光晕，让「绿点」在浅色背景上不至于太小。 */
@Composable
fun StatusDot(online: Boolean, size: androidx.compose.ui.unit.Dp = 9.dp) {
    val p = LocalRuyiPalette.current
    val color = if (online) p.success else p.textTertiary
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size * 2.2f)
                .background(color.copy(alpha = if (online) 0.14f else 0f), CircleShape),
        )
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

/** 圆角标签：只承载一个短词（在线 / 运行中 / 已连接）。 */
@Composable
fun Pill(text: String, tone: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = 0.12f))
            .padding(horizontal = Space.s, vertical = 3.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = tone,
            maxLines = 1,
        )
    }
}

/** 区块标题：小号、带字距，用来把页面切成「身份 / 连接 / 危险区」这样的段落。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val p = LocalRuyiPalette.current
    Text(
        text,
        modifier = modifier.padding(start = Space.xs, bottom = Space.s),
        style = MaterialTheme.typography.labelMedium,
        color = p.textTertiary,
    )
}

/** 键值行：左侧说明、右侧值。值允许换行（指纹很长）。 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color? = null,
) {
    val p = LocalRuyiPalette.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = p.textTertiary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: p.text,
            fontFamily = if (mono) Mono else null,
        )
    }
}

/* ------------------------------------------------------------------ 状态 */

/** 空态：一句主文案 + 一句解释 + 可选动作。 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val p = LocalRuyiPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xxl, vertical = Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .background(p.accentSoft, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) { GlyphMonitor(size = 26.dp, tint = p.onAccentSoft) }
        Spacer(Modifier.height(Space.xs))
        Text(title, style = MaterialTheme.typography.titleMedium, color = p.text, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.xs))
            GhostButton(actionLabel, onAction)
        }
    }
}

/**
 * 中性提示条：只解释「为什么现在是这个状态」，不带重试动作。
 *
 * 和 [InlineError] 的区别是语气：错误是「刚刚失败了」，提示是「这不是新的失败，
 * 是上次留下的结果」。混用会让登录页一进来就像出事了。
 */
@Composable
fun NoticeBar(text: String, modifier: Modifier = Modifier) {
    val p = LocalRuyiPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(p.warning.copy(alpha = if (p.dark) 0.16f else 0.09f))
            .padding(horizontal = Space.m, vertical = Space.m),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = p.warning)
    }
}

/**
 * 行内错误。`code` 单独一行等宽显示：真机排查时这一行比任何文案都值钱，
 * 但普通用户不该被它打扰，所以字号压到最小、颜色降到三级。
 */
@Composable
fun InlineError(
    message: String,
    modifier: Modifier = Modifier,
    code: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val p = LocalRuyiPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(p.danger.copy(alpha = if (p.dark) 0.14f else 0.06f))
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = p.danger)
        if (code != null) {
            Text("诊断码 $code", style = MaterialTheme.typography.labelSmall, color = p.textTertiary, fontFamily = Mono)
        }
        if (retryLabel != null && onRetry != null) {
            Text(
                retryLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRetry)
                    .padding(vertical = Space.xs, horizontal = Space.xs),
                style = MaterialTheme.typography.labelMedium,
                color = p.accent,
            )
        }
    }
}

/** 三点脉冲：表示「对方正在输出」。 */
@Composable
fun TypingDots(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 620, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(5.dp)
                    .alpha(alpha)
                    .background(color, CircleShape),
            )
        }
    }
}

/** 骨架行：加载列表时占位，比转圈更能表达「这里将会出现一列东西」。 */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    val p = LocalRuyiPalette.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Column(modifier.fillMaxWidth().padding(vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Box(
            Modifier
                .fillMaxWidth(0.55f)
                .height(13.dp)
                .alpha(alpha)
                .background(p.cardPressed, RoundedCornerShape(6.dp)),
        )
        Box(
            Modifier
                .fillMaxWidth(0.3f)
                .height(10.dp)
                .alpha(alpha)
                .background(p.cardPressed, RoundedCornerShape(5.dp)),
        )
    }
}
