package com.xxt.zl.ai.ruyiremote.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono

/**
 * 8 格配对码输入。
 *
 * 为什么不用一个普通输入框：配对码是「看着电脑念、在手机上敲」的动作，中间那个
 * 连字符恰恰是最容易敲错的字符（真机上 IME 会给出全角横杠）。所以视觉上做成
 * 4+4 两段格子，内部只存 8 个字母数字，提交时再由 [normalizeRegistrationCode]
 * 补回 ASCII 连字符——用户永远不需要自己敲 `-`。
 */
@Composable
fun PairingCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDone: () -> Unit = {},
) {
    val p = LocalRuyiPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val caret = rememberInfiniteTransition(label = "caret")
    val caretAlpha by caret.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(560), repeatMode = RepeatMode.Reverse),
        label = "caretAlpha",
    )

    val activeIndex = value.length.coerceAtMost(7)

    BasicTextField(
        value = value,
        onValueChange = { onValueChange(sanitizeCodeInput(it)) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        interactionSource = interaction,
        // 真正的文字与光标都不可见：可见内容全部由下面的格子绘制，输入框只负责
        // 收键、收粘贴、维持焦点。字号压到 1sp 是为了让 IME 认为这里没有内容可撑。
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                onDone()
            },
        ),
        decorationBox = { inner ->
            Box {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (index in 0 until 8) {
                        if (index == 4) Spacer(Modifier.width(6.dp))
                        CodeCell(
                            char = value.getOrNull(index),
                            active = focused && index == activeIndex && value.length < 8,
                            caretAlpha = caretAlpha,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // 1dp 的落点：只是为了把 innerTextField 放进组合里（它携带焦点与输入
                // 连接），实际渲染由上面的格子完成。
                Box(Modifier.size(1.dp)) { inner() }
            }
        },
    )
}

@Composable
private fun CodeCell(
    char: Char?,
    active: Boolean,
    caretAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val p = LocalRuyiPalette.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(shape)
            .background(if (char != null) p.cardPressed else p.card)
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = when {
                    active -> p.accent
                    char != null -> p.hairline
                    else -> p.hairline
                },
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            char != null -> Text(
                text = char.uppercaseChar().toString(),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                fontFamily = Mono,
                color = p.text,
            )

            active -> Box(
                Modifier
                    .size(width = 2.dp, height = 20.dp)
                    .alpha(caretAlpha)
                    .background(p.accent, RoundedCornerShape(1.dp)),
            )

            else -> Box(
                Modifier
                    .size(width = 8.dp, height = 2.dp)
                    .background(p.hairline, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/**
 * 只留字母数字、统一大写、最多 8 位。
 *
 * 粘贴进来的 `ABCD-1234`、输入法给的全角连字符、带空格的一整串，都会在这里被
 * 抹平成同一份 8 位原始码。
 */
fun sanitizeCodeInput(raw: String): String =
    raw.filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
        .uppercase()
        .take(8)
