package com.xxt.zl.ai.ruyiremote.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 语义化调色板。
 *
 * 不直接用 `MaterialTheme.colorScheme` 的原因：Material 的角色名（`surfaceVariant`、
 * `tertiary`…）表达的是「组件默认色」，而这里需要的是「这是什么意思」——在线/离线、
 * 用户气泡/助手气泡、发丝线。两者混用会让「在线绿点」这种语义在深色模式下失配。
 * 所以业务色单独成一层，M3 的 colorScheme 只负责组件默认值，两者由同一份常量派生。
 */
data class RuyiPalette(
    val dark: Boolean,
    val bg: Color,
    val bgElevated: Color,
    val card: Color,
    val cardPressed: Color,
    val hairline: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val bubbleUser: Color,
    val onBubbleUser: Color,
    val bubbleAssistant: Color,
    val onBubbleAssistant: Color,
    val bubbleAssistantBorder: Color,
    /* 对话页专用的「纸张」色：蓝调渐变底 + 悬浮白胶囊 + 软蓝用户气泡。 */
    val chatTop: Color,
    val chatMid: Color,
    val chatBottom: Color,
    val float: Color,
    val chip: Color,
    /* 输入栏里「+」的底：中性灰而不是品牌色。它只是个入口，抢走注意力反而干扰。 */
    val chipNeutral: Color,
    val bubbleUserSoft: Color,
    val onBubbleUserSoft: Color,
)

private val LightPalette = RuyiPalette(
    dark = false,
    bg = Color(0xFFF5F5F8),
    bgElevated = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardPressed = Color(0xFFEFEFF4),
    hairline = Color(0xFFE6E6EE),
    text = Color(0xFF121217),
    textSecondary = Color(0xFF5C5C6B),
    textTertiary = Color(0xFF8C8C9C),
    accent = Color(0xFF4F46E5),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEDECFF),
    onAccentSoft = Color(0xFF2A2394),
    success = Color(0xFF14A06B),
    warning = Color(0xFFC07200),
    danger = Color(0xFFD92D20),
    bubbleUser = Color(0xFF4F46E5),
    onBubbleUser = Color(0xFFFFFFFF),
    bubbleAssistant = Color(0xFFFFFFFF),
    onBubbleAssistant = Color(0xFF121217),
    bubbleAssistantBorder = Color(0xFFE6E6EE),
    chatTop = Color(0xFFE6EEFA),
    chatMid = Color(0xFFF2F7FD),
    chatBottom = Color(0xFFE3ECFA),
    float = Color(0xFFFFFFFF),
    chip = Color(0xFFE6EFFB),
    chipNeutral = Color(0xFFEDEFF3),
    bubbleUserSoft = Color(0xFFD9E5F8),
    onBubbleUserSoft = Color(0xFF1A2B47),
)

private val DarkPalette = RuyiPalette(
    dark = true,
    bg = Color(0xFF0B0B0F),
    bgElevated = Color(0xFF121218),
    card = Color(0xFF17171F),
    cardPressed = Color(0xFF20202A),
    hairline = Color(0xFF272733),
    text = Color(0xFFF1F1F6),
    textSecondary = Color(0xFFA5A5B4),
    textTertiary = Color(0xFF75758A),
    accent = Color(0xFF8B87FF),
    onAccent = Color(0xFF12103A),
    accentSoft = Color(0xFF212047),
    onAccentSoft = Color(0xFFC9C6FF),
    success = Color(0xFF3DD68C),
    warning = Color(0xFFF0A32E),
    danger = Color(0xFFFF6B6B),
    bubbleUser = Color(0xFF4F46E5),
    onBubbleUser = Color(0xFFFFFFFF),
    bubbleAssistant = Color(0xFF1C1C25),
    onBubbleAssistant = Color(0xFFECECF2),
    bubbleAssistantBorder = Color(0xFF2A2A37),
    chatTop = Color(0xFF0A0E16),
    chatMid = Color(0xFF111827),
    chatBottom = Color(0xFF0A0E16),
    float = Color(0xFF1B2130),
    chip = Color(0xFF26303F),
    chipNeutral = Color(0xFF2B3442),
    bubbleUserSoft = Color(0xFF2A3954),
    onBubbleUserSoft = Color(0xFFE7EEFB),
)

private fun RuyiPalette.toColorScheme() = if (dark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        secondary = textSecondary,
        onSecondary = bg,
        secondaryContainer = cardPressed,
        onSecondaryContainer = text,
        tertiary = success,
        onTertiary = bg,
        background = bg,
        onBackground = text,
        surface = card,
        onSurface = text,
        surfaceVariant = cardPressed,
        onSurfaceVariant = textSecondary,
        surfaceTint = accent,
        inverseSurface = text,
        inverseOnSurface = bg,
        error = danger,
        onError = Color(0xFF1A0505),
        errorContainer = Color(0xFF3B1512),
        onErrorContainer = Color(0xFFFFD9D6),
        outline = hairline,
        outlineVariant = hairline,
        scrim = Color(0xFF000000),
        surfaceContainer = card,
        surfaceContainerHigh = cardPressed,
        surfaceContainerHighest = cardPressed,
        surfaceContainerLow = bgElevated,
        surfaceContainerLowest = bg,
        surfaceDim = bg,
        surfaceBright = card,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        secondary = textSecondary,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = cardPressed,
        onSecondaryContainer = text,
        tertiary = success,
        onTertiary = Color(0xFFFFFFFF),
        background = bg,
        onBackground = text,
        surface = card,
        onSurface = text,
        surfaceVariant = cardPressed,
        onSurfaceVariant = textSecondary,
        surfaceTint = accent,
        inverseSurface = text,
        inverseOnSurface = Color(0xFFFFFFFF),
        error = danger,
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFEECEB),
        onErrorContainer = Color(0xFF7A1210),
        outline = hairline,
        outlineVariant = hairline,
        scrim = Color(0xFF000000),
        surfaceContainer = card,
        surfaceContainerHigh = card,
        surfaceContainerHighest = cardPressed,
        surfaceContainerLow = bgElevated,
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceDim = bg,
        surfaceBright = Color(0xFFFFFFFF),
    )
}

/**
 * 字号阶梯。比 M3 默认更紧的 tracking + 更大的标题字重差，
 * 目的是让「页面标题 / 区块标题 / 正文」在一屏里一眼能分级。
 */
private val RuyiTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.6).sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp,
        fontWeight = FontWeight.Medium,
    ),
)

/** 圆角：卡片 20、控件 14、气泡 18、胶囊全圆。 */
private val RuyiShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/** 间距阶梯，避免 3dp/5dp/7dp 这类随手值散落各处。 */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val xxxl = 40.dp

    /** 页面左右留白。 */
    val page = 20.dp
}

/** 代码/指纹/地址一律等宽，方便逐字符核对。 */
val Mono = FontFamily.Monospace

val LocalRuyiPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun RuyiTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (dark) DarkPalette else LightPalette
    val view = LocalView.current

    // 状态栏图标跟随主题。themes.xml 里写死的是浅色主题（深色图标），
    // 系统切到深色时必须在这里翻过来，否则深底深字什么也看不见。
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    CompositionLocalProvider(LocalRuyiPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(),
            typography = RuyiTypography,
            shapes = RuyiShapes,
            content = content,
        )
    }
}
