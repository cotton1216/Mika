package com.xxt.zl.ai.ruyiremote.session

import java.net.URI

sealed interface TextSegment {
    data class Prose(val text: String) : TextSegment
    data class Code(val language: String?, val body: String) : TextSegment
}

/** 围栏先于正文解析；流式未闭合的围栏也立即显示为代码。 */
fun splitFencedCode(text: String): List<TextSegment> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<TextSegment>()
    val prose = mutableListOf<String>()
    val code = mutableListOf<String>()
    var fence: String? = null
    var language: String? = null
    fun flushProse() {
        val body = prose.joinToString("\n").trim('\n')
        if (body.isNotBlank()) out += TextSegment.Prose(body)
        prose.clear()
    }
    for (line in text.replace("\r\n", "\n").lines()) {
        val opening = fencePattern.matchEntire(line)
        val current = fence
        if (current != null) {
            val candidate = line.trimStart()
            val closes = line.takeWhile { it == ' ' }.length <= 3 &&
                candidate.takeWhile { it == current[0] }.length >= current.length &&
                candidate.dropWhile { it == current[0] }.isBlank()
            if (closes) {
                out += TextSegment.Code(language, code.joinToString("\n").trimEnd('\n'))
                code.clear()
                fence = null
            } else code += line
        } else if (opening != null &&
            !(opening.groupValues[1][0] == '`' && opening.groupValues[2].contains('`'))
        ) {
            flushProse()
            fence = opening.groupValues[1]
            language = opening.groupValues[2].trim().takeIf { it.isNotEmpty() }
                ?.substringBefore(' ')
        } else prose += line
    }
    if (fence != null) out += TextSegment.Code(language, code.joinToString("\n").trimEnd('\n'))
    else flushProse()
    return out
}

private val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val headingPattern = Regex("^ {0,3}(#{1,6})[ \\t]+(.+?)(?:[ \\t]+#+)?[ \\t]*$")
private val listPattern = Regex("^( *)([-+*]|[0-9]+[.)])\\s+(.*)$")
private val quotePattern = Regex("^ {0,3}> ?(.*)$")
private val dividerPattern = Regex("^:?-{3,}:?$")

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class ListItem(val marker: String, val text: String, val depth: Int) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String?, val body: String) : MarkdownBlock
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<MarkdownAlignment>,
    ) : MarkdownBlock
}

enum class MarkdownAlignment { Start, Center, End }

/** 只解析可展示的 Markdown 子集；HTML 始终是普通文本，不加载图片或远程资源。 */
fun parseMarkdown(text: String): List<MarkdownBlock> = splitFencedCode(text).flatMap { segment ->
    when (segment) {
        is TextSegment.Code -> listOf(MarkdownBlock.Code(segment.language, segment.body))
        is TextSegment.Prose -> parseProse(segment.text)
    }
}

private fun parseProse(text: String): List<MarkdownBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
        paragraph.clear()
    }
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val heading = headingPattern.matchEntire(line)
        val item = listPattern.matchEntire(line)
        val quote = quotePattern.matchEntire(line)
        val header = splitTableRow(line)
        val separator = lines.getOrNull(index + 1)?.let(::splitTableRow)
        val table = line.contains('|') && header.isNotEmpty() && separator != null &&
            header.size == separator.size && separator.all { dividerPattern.matches(it) }
        when {
            line.isBlank() -> flushParagraph()
            table -> {
                flushParagraph()
                val alignments = separator!!.map {
                    when {
                        it.startsWith(':') && it.endsWith(':') -> MarkdownAlignment.Center
                        it.endsWith(':') -> MarkdownAlignment.End
                        else -> MarkdownAlignment.Start
                    }
                }
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|') &&
                    headingPattern.matchEntire(lines[index]) == null &&
                    listPattern.matchEntire(lines[index]) == null &&
                    quotePattern.matchEntire(lines[index]) == null
                ) {
                    val cells = splitTableRow(lines[index])
                    rows += List(header.size) { cells.getOrElse(it) { "" } }
                    index++
                }
                blocks += MarkdownBlock.Table(header, rows, alignments)
                continue
            }
            heading != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            }
            item != null -> {
                flushParagraph()
                val marker = item.groupValues[2].let { if (it.length == 1) "•" else it }
                blocks += MarkdownBlock.ListItem(marker, item.groupValues[3], item.groupValues[1].length / 2)
            }
            quote != null -> {
                flushParagraph()
                val quoted = mutableListOf(quote.groupValues[1])
                while (index + 1 < lines.size) {
                    val next = quotePattern.matchEntire(lines[index + 1]) ?: break
                    quoted += next.groupValues[1]
                    index++
                }
                blocks += MarkdownBlock.Quote(quoted.joinToString("\n"))
            }
            else -> paragraph += line
        }
        index++
    }
    flushParagraph()
    return blocks
}

/** 转义竖线与代码跨度内的竖线不作为表格分列符。 */
private fun splitTableRow(line: String): List<String> {
    val text = line.trim()
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var codeTicks = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == '\\' && index + 1 < text.length -> {
                cell.append(char).append(text[index + 1])
                index += 2
            }
            char == '`' -> {
                val count = text.substring(index).takeWhile { it == '`' }.length
                if (codeTicks == 0) codeTicks = count else if (codeTicks == count) codeTicks = 0
                cell.append("`".repeat(count))
                index += count
            }
            char == '|' && codeTicks == 0 -> {
                cells += cell.toString().trim()
                cell.clear()
                index++
            }
            else -> { cell.append(char); index++ }
        }
    }
    cells += cell.toString().trim()
    if (text.startsWith('|') && cells.firstOrNull() == "") cells.removeAt(0)
    if (text.endsWith('|') && cells.lastOrNull() == "") cells.removeAt(cells.lastIndex)
    return cells
}

data class MarkdownInline(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

fun isSafeMarkdownUrl(url: String): Boolean {
    if (url.any { it.isWhitespace() || it.isISOControl() } || '\\' in url) return false
    return runCatching {
        val uri = URI(url)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)
}

fun parseMarkdownInline(text: String): List<MarkdownInline> = parseInline(text, false, 0)

/**
 * 把一条回复折成输入栏里的引用草稿。
 *
 * 引用是纯本地动作：不发 RPC、不改会话日志，只按 Markdown 引用块格式把文字放进输入框，
 * 用户接着写问题。长回复必须截断——BasicTextField 里放上万字会让每次按键都重新布局整段
 * 文本，和之前流式回复 ANR 是同一类问题。空回复不改动原有草稿，避免误触清掉已写内容。
 */
internal fun quoteIntoDraft(draft: String, reply: String, maxChars: Int = 1500): String {
    require(maxChars > 0) { "maxChars 必须为正数" }
    val body = reply.replace("\r\n", "\n").trim()
    if (body.isEmpty()) return draft
    val clipped = if (body.length > maxChars) body.take(maxChars).trimEnd() + "…" else body
    val quoted = clipped.lines().joinToString("\n") { line ->
        if (line.isBlank()) ">" else "> ${line.trimEnd()}"
    }
    // 引用块必须与已有草稿隔开一个空行，否则 Markdown 会把它接在上一段后面。
    val separator = when {
        draft.isEmpty() || draft.endsWith("\n\n") -> ""
        draft.endsWith("\n") -> "\n"
        else -> "\n\n"
    }
    return draft + separator + quoted + "\n\n"
}

private fun parseInline(text: String, bold: Boolean, depth: Int): List<MarkdownInline> {
    if (depth > 16) return listOf(MarkdownInline(text, bold))
    val result = mutableListOf<MarkdownInline>()
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) result += MarkdownInline(plain.toString(), bold)
        plain.clear()
    }
    var index = 0
    while (index < text.length) {
        when {
            text[index] == '\\' && index + 1 < text.length &&
                text[index + 1] in "\\`*_{}[]()#+-.!|>" -> {
                plain.append(text[index + 1])
                index += 2
            }
            text[index] == '`' -> {
                val count = text.substring(index).takeWhile { it == '`' }.length
                val delimiter = "`".repeat(count)
                val end = text.indexOf(delimiter, index + count)
                if (end >= 0) {
                    flush()
                    result += MarkdownInline(text.substring(index + count, end), bold, code = true)
                    index = end + count
                } else {
                    // 未闭合的行内标记保留原文，避免流式内容丢字。
                    plain.append(delimiter)
                    index += count
                }
            }
            text.startsWith("**", index) || text.startsWith("__", index) -> {
                val delimiter = text.substring(index, index + 2)
                val end = text.indexOf(delimiter, index + 2)
                if (end > index + 2) {
                    flush()
                    result += parseInline(text.substring(index + 2, end), true, depth + 1)
                    index = end + 2
                } else { plain.append(delimiter); index += 2 }
            }
            text[index] == '[' && (index == 0 || text[index - 1] != '!') -> {
                val labelEnd = text.indexOf("](", index + 1)
                val urlEnd = if (labelEnd >= 0) linkEnd(text, labelEnd + 2) else -1
                if (urlEnd >= 0) {
                    val url = text.substring(labelEnd + 2, urlEnd)
                    flush()
                    val label = parseInline(text.substring(index + 1, labelEnd), bold, depth + 1)
                    if (isSafeMarkdownUrl(url)) {
                        result += label.map { it.copy(url = url) }
                    } else {
                        // 文件路径这类不是能打开的链接。原样保留会让气泡里多出
                        // `[名字](</Users/...>)` 一整串机器格式，只留标签更可读。
                        result += label
                    }
                    index = urlEnd + 1
                } else { plain.append(text[index]); index++ }
            }
            else -> { plain.append(text[index]); index++ }
        }
    }
    flush()
    return result
}

private fun linkEnd(text: String, start: Int): Int {
    var nested = 0
    for (index in start until text.length) {
        when (text[index]) {
            '(' -> nested++
            ')' -> if (nested == 0) return index else nested--
            '\n' -> return -1
        }
    }
    return -1
}
