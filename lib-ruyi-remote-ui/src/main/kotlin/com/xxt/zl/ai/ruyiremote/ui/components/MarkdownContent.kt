package com.xxt.zl.ai.ruyiremote.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.caverock.androidsvg.SVG
import com.xxt.zl.ai.ruyiremote.session.MarkdownAlignment
import com.xxt.zl.ai.ruyiremote.session.MarkdownBlock
import com.xxt.zl.ai.ruyiremote.session.isSafeMarkdownUrl
import com.xxt.zl.ai.ruyiremote.session.parseMarkdown
import com.xxt.zl.ai.ruyiremote.session.parseMarkdownInline
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 纯 Compose 阅读视图；不使用 WebView，不解释 HTML，不自动加载外部资源。
 *
 * 唯一的位图来源是 ` ```svg ` 代码块：由本机 AndroidSVG 解析后光栅化，仍然不联网、
 * 不执行脚本、不引用外部资源。解析不了就降级回原来的代码块，不会变成空白。
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    onLoadImage: (suspend (String) -> ByteArray)? = null,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val p = LocalRuyiPalette.current
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Paragraph -> MarkdownText(block.text, onLoadImage = onLoadImage)
                    is MarkdownBlock.Heading -> MarkdownText(
                        block.text,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        modifier = Modifier.padding(top = Space.xs),
                        onLoadImage = onLoadImage,
                    )
                    is MarkdownBlock.ListItem -> Row(
                        Modifier.padding(start = (block.depth.coerceAtMost(6) * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        Text(block.marker, color = p.textSecondary, style = MaterialTheme.typography.bodyLarge)
                        MarkdownText(block.text, Modifier.weight(1f), onLoadImage = onLoadImage)
                    }
                    is MarkdownBlock.Quote -> Row(
                        Modifier.fillMaxWidth().background(p.cardPressed, RoundedCornerShape(8.dp)),
                    ) {
                        Text("│", color = p.accent, modifier = Modifier.padding(start = Space.s, top = Space.s))
                        MarkdownText(block.text, Modifier.weight(1f).padding(Space.s), onLoadImage = onLoadImage)
                    }
                    is MarkdownBlock.Code ->
                        if (block.looksLikeSvg()) SvgContent(block) else MarkdownCodeBlock(block)
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
    onLoadImage: (suspend (String) -> ByteArray)? = null,
) {
    val p = LocalRuyiPalette.current
    val uriHandler = LocalUriHandler.current
    val spans = remember(text) { parseMarkdownInline(text) }
    // 没有可用 loader 或这段文字没有图片时，走原来的纯文本路径（零额外布局）。
    val hasImage = onLoadImage != null && spans.any { it.imagePath != null }
    if (!hasImage) {
        val annotated = remember(spans, p, uriHandler) {
            buildAnnotatedString {
                spans.forEach { span -> appendStyledSpan(span, p, uriHandler) }
            }
        }
        Text(annotated, modifier = modifier, style = style, color = p.onBubbleAssistant, textAlign = textAlign)
        return
    }

    // 有图片：按 imagePath 把 spans 切成文字段/图片段，图片单独一行异步加载。
    // 先把连续的文字 span 合并成段，避免在 @Composable 里用局部函数调 Text。
    data class Run(val text: List<com.xxt.zl.ai.ruyiremote.session.MarkdownInline>, val imagePath: String?)
    val runs = mutableListOf<Run>()
    var buf = mutableListOf<com.xxt.zl.ai.ruyiremote.session.MarkdownInline>()
    spans.forEach { span ->
        val path = span.imagePath
        if (path == null) {
            buf += span
        } else {
            if (buf.isNotEmpty()) { runs += Run(buf.toList(), null); buf = mutableListOf() }
            runs += Run(emptyList(), path)
        }
    }
    if (buf.isNotEmpty()) runs += Run(buf.toList(), null)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        runs.forEach { run ->
            val path = run.imagePath
            if (path == null) {
                val annotated = buildAnnotatedString {
                    run.text.forEach { appendStyledSpan(it, p, uriHandler) }
                }
                Text(annotated, style = style, color = p.onBubbleAssistant, textAlign = textAlign)
            } else {
                // run.text 为空，图片 alt 取原 span 的文本。
                val alt = spans.firstOrNull { it.imagePath == path }?.text ?: "图片"
                WorkspaceImage(path = path, alt = alt, load = onLoadImage!!)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendStyledSpan(
    span: com.xxt.zl.ai.ruyiremote.session.MarkdownInline,
    p: com.xxt.zl.ai.ruyiremote.ui.theme.RuyiPalette,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
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
                        runCatching { if (isSafeMarkdownUrl(url)) uriHandler.openUri(url) }
                    },
                ),
            ) { append(span.text) }
        } else append(span.text)
    }
}

/** T15：工作区图片异步加载，失败时降级显示 alt 文字（不留空白、不崩溃）。 */
@Composable
private fun WorkspaceImage(
    path: String,
    alt: String,
    load: suspend (String) -> ByteArray,
) {
    val p = LocalRuyiPalette.current
    val state by produceState<WsImg>(WsImg.Loading, path, load) {
        value = withContext(Dispatchers.IO) {
            runCatching { load(path) }
                .mapCatching { bytes -> WsImg.Ready(decodeWorkspaceBitmap(bytes)) }
                .getOrElse { error ->
                    android.util.Log.w("MarkdownContent", "工作区图片加载失败: $path", error)
                    WsImg.Failed
                }
        }
    }
    when (val s = state) {
        is WsImg.Loading -> Box(
            Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(12.dp)).background(p.cardPressed),
        )
        is WsImg.Failed -> Text(alt, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        is WsImg.Ready -> Image(
            bitmap = s.bitmap.asImageBitmap(),
            contentDescription = alt,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .border(1.dp, p.hairline, RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillWidth,
        )
    }
}

private sealed interface WsImg {
    data object Loading : WsImg
    data object Failed : WsImg
    data class Ready(val bitmap: Bitmap) : WsImg
}

/** 把工作区图片字节解成非空 Bitmap；失败抛异常由上层 runCatching 兜底。 */
private fun decodeWorkspaceBitmap(bytes: ByteArray): Bitmap =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalArgumentException("图片解码失败")

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

/**
 * ` ```svg ` 代码块直接画成图。
 *
 * 远程主机把图表作为 SVG 下发时，按代码块渲染等于让人读源码。这里在**本机**光栅化：
 * 不联网、不加载外部资源、不执行脚本，与「纯 Compose 阅读视图」的约束一致。
 * 三条降级线——超长、解析失败、尺寸无效——一律退回代码块，宁可难看也不留空白。
 */
@Composable
private fun SvgContent(block: MarkdownBlock.Code) {
    val p = LocalRuyiPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        val render by produceState<SvgRender>(SvgRender.Loading, block.body, widthPx) {
            value = withContext(Dispatchers.Default) { renderSvg(block.body, widthPx) }
        }
        when (val current = render) {
            SvgRender.Loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.cardPressed),
            )

            SvgRender.Failed -> MarkdownCodeBlock(block)

            is SvgRender.Ready -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = "SVG 图形",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, p.hairline, RoundedCornerShape(12.dp)),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

private sealed interface SvgRender {
    data object Loading : SvgRender
    data class Ready(val bitmap: Bitmap) : SvgRender
    data object Failed : SvgRender
}

/** 超过这个长度的正文不当作图处理：正常图表都在几 KB，几十万字符只会是误判或攻击。 */
private const val MAX_SVG_CHARS = 200_000

/** 光栅化宽度上限，防止一张超宽画布把内存吃满。 */
private const val MAX_SVG_WIDTH_PX = 1_400

/** 高宽比上限：竖长条画到下个屏幕外没有意义，裁掉高度交给内容自己看。 */
private const val MAX_SVG_ASPECT = 2.6f

/**
 * 按尺寸缓存位图：消息列表一滚就把离屏项回收了，没有缓存的话来回滚会重复光栅化。
 * 用字节数而不是条目数限容，避免一张大图就把小图挤光。
 */
private object SvgBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

private fun MarkdownBlock.Code.looksLikeSvg(): Boolean {
    val lang = language?.trim()?.lowercase()
    if (lang == "svg" || lang == "svg+xml") return true
    // 有些 Host 把 SVG 标成 xml 或干脆不标语言：这时只认正文开头确实是 <svg。
    if (lang != null && lang != "xml") return false
    return body.trimStart().startsWith("<svg", ignoreCase = true)
}

private fun renderSvg(body: String, maxWidthPx: Int): SvgRender {
    if (maxWidthPx <= 0 || body.length > MAX_SVG_CHARS) return SvgRender.Failed
    val widthPx = minOf(maxWidthPx, MAX_SVG_WIDTH_PX)
    val key = "${body.hashCode()}:$widthPx"
    SvgBitmapCache.get(key)?.let { return SvgRender.Ready(it) }
    return try {
        val document = SVG.getFromString(body)
        val width = document.documentWidth
        val height = document.documentHeight
        if (width <= 0f || height <= 0f) return SvgRender.Failed
        val heightCap = (widthPx * MAX_SVG_ASPECT).roundToInt()
        val heightPx = (widthPx * height / width).roundToInt().coerceAtMost(heightCap)
        if (heightPx <= 0) return SvgRender.Failed
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPicture(document.renderToPicture(widthPx, heightPx))
        SvgBitmapCache.put(key, bitmap)
        SvgRender.Ready(bitmap)
    } catch (error: Throwable) {
        // 解析失败是正常分支（旧 Host、非标准 SVG），不记日志污染现场。
        SvgRender.Failed
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table) {    val p = LocalRuyiPalette.current
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
