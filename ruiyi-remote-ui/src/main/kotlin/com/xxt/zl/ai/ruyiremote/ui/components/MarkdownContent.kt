package com.xxt.zl.ai.ruyiremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.session.MarkdownAlignment
import com.xxt.zl.ai.ruyiremote.session.MarkdownBlock
import com.xxt.zl.ai.ruyiremote.session.isSafeMarkdownUrl
import com.xxt.zl.ai.ruyiremote.session.parseMarkdown
import com.xxt.zl.ai.ruyiremote.session.parseMarkdownInline
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space

/** 纯 Compose 阅读视图；不使用 WebView，不解释 HTML，不自动加载外部资源。 */
@Composable
fun MarkdownContent(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val p = LocalRuyiPalette.current
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Paragraph -> MarkdownText(block.text)
                    is MarkdownBlock.Heading -> MarkdownText(
                        block.text,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        modifier = Modifier.padding(top = Space.xs),
                    )
                    is MarkdownBlock.ListItem -> Row(
                        Modifier.padding(start = (block.depth.coerceAtMost(6) * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        Text(block.marker, color = p.textSecondary, style = MaterialTheme.typography.bodyLarge)
                        MarkdownText(block.text, Modifier.weight(1f))
                    }
                    is MarkdownBlock.Quote -> Row(
                        Modifier.fillMaxWidth().background(p.cardPressed, RoundedCornerShape(8.dp)),
                    ) {
                        Text("│", color = p.accent, modifier = Modifier.padding(start = Space.s, top = Space.s))
                        MarkdownText(block.text, Modifier.weight(1f).padding(Space.s))
                    }
                    is MarkdownBlock.Code -> MarkdownCodeBlock(block)
                    is MarkdownBlock.Table -> MarkdownTable(block)
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    textAlign: TextAlign = TextAlign.Start,
) {
    val p = LocalRuyiPalette.current
    val uriHandler = LocalUriHandler.current
    val spans = remember(text) { parseMarkdownInline(text) }
    val annotated = remember(spans, p, uriHandler) {
        buildAnnotatedString {
            spans.forEach { span ->
                val spanStyle = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontFamily = if (span.code) Mono else null,
                    background = if (span.code) p.cardPressed else androidx.compose.ui.graphics.Color.Unspecified,
                )
                withStyle(spanStyle) {
                    val url = span.url
                    if (url != null && isSafeMarkdownUrl(url)) {
                        withLink(
                            LinkAnnotation.Url(
                                url,
                                styles = TextLinkStyles(SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline)),
                                linkInteractionListener = {
                                    // 浏览器可能未安装；无可处理的应用时保留当前阅读页面。
                                    runCatching { if (isSafeMarkdownUrl(url)) uriHandler.openUri(url) }
                                },
                            ),
                        ) { append(span.text) }
                    } else append(span.text)
                }
            }
        }
    }
    Text(annotated, modifier = modifier, style = style, color = p.onBubbleAssistant, textAlign = textAlign)
}

@Composable
private fun MarkdownCodeBlock(block: MarkdownBlock.Code) {
    val p = LocalRuyiPalette.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.body) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.cardPressed),
    ) {
        DisableSelection {
            Row(
                Modifier.fillMaxWidth().padding(start = Space.m, end = Space.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    block.language ?: "代码",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = p.textSecondary,
                    maxLines = 1,
                )
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(block.body))
                        copied = true
                    },
                    enabled = block.body.isNotEmpty(),
                ) { Text(if (copied) "已复制" else "复制代码", style = MaterialTheme.typography.labelMedium) }
            }
        }
        Text(
            block.body,
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = Space.m, end = Space.m, bottom = Space.m),
            color = p.text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = Mono,
            softWrap = false,
        )
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table) {
    val p = LocalRuyiPalette.current
    Column(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .border(1.dp, p.hairline, RoundedCornerShape(8.dp)),
    ) {
        (listOf(table.header) + table.rows).forEachIndexed { rowIndex, row ->
            Row(Modifier.background(if (rowIndex == 0) p.cardPressed else p.bubbleAssistant)) {
                row.forEachIndexed { columnIndex, cell ->
                    MarkdownText(
                        cell,
                        Modifier.width(160.dp).padding(Space.s),
                        style = if (rowIndex == 0) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyMedium,
                        textAlign = when (table.alignments[columnIndex]) {
                            MarkdownAlignment.Start -> TextAlign.Start
                            MarkdownAlignment.Center -> TextAlign.Center
                            MarkdownAlignment.End -> TextAlign.End
                        },
                    )
                }
            }
        }
    }
}
