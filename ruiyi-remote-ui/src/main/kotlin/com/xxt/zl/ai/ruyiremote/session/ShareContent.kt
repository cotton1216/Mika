package com.xxt.zl.ai.ruyiremote.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「分享」的统一内容模型：范围、隐私开关、导出文本都在这里算完，UI 只负责展示。
 *
 * 与参考端 `dsh-Go/app/lib/ui/share/` 对齐的三条硬约束：
 * 1. 标题、模型、主机、耗时等只用 Host 真实下发的数据，不编造占位值。
 * 2. 脱敏不是可选项，永远生效，且真的替换文本（不是只写一行「密钥已隐藏」）。
 * 3. 预览与导出必须是同一份 [ShareContent.markdown]，不能预览一套、导出另一套。
 *
 * 这个文件刻意不依赖 Android / Compose，所有逻辑都能在 JVM 单测里直接覆盖。
 */

/** 分享多少内容。 */
enum class ShareScope { Reply, Picked, Whole }

/** 消息正文之外还带什么。脱敏不在这三个开关里，永远开启。 */
data class SharePrivacy(
    val thinking: Boolean = true,
    val tools: Boolean = true,
    val images: Boolean = true,
)

/** 四种出口。未实现的两个必须禁用并给出真实原因，不放一个点了没反应的假按钮。 */
enum class ShareOutput { Text, File, Image, Link }

/** null = 可用；非 null = 不能用的原因。 */
fun ShareOutput.unavailableReason(): String? = when (this) {
    ShareOutput.Text, ShareOutput.File -> null
    ShareOutput.Image -> "图片导出需要长图渲染与分享 URI 授权，尚未实现"
    ShareOutput.Link -> "链接分享需要上传服务与访问范围鉴权，不能无授权公开会话"
}

/** ACTION_SEND 走 Binder 事务，留足余量；超过这个长度改用文件导出，不静默截断。 */
const val TEXT_SHARE_LIMIT = 80_000

data class ShareContent(
    val markdown: String,
    val messages: Int,
    val toolCalls: Int,
    val images: Int,
    val redactions: Int,
    val selectedKeys: List<String>,
    val scope: ShareScope,
) {
    val isEmpty: Boolean get() = messages == 0 && toolCalls == 0
    val textTooLarge: Boolean get() = markdown.length > TEXT_SHARE_LIMIT
}

/**
 * 按范围挑行，保持会话原顺序。
 *
 * Reply = 这条回复 + 它上面最近的一个提问（中间的工具行也保留，但能否输出由
 * [SharePrivacy.tools] 决定）。anchor 找不到时返回空，绝不悄悄退化成「分享整段」——
 * 那会在用户以为只分享一条时把整个会话发出去。
 */
internal fun selectShareRows(
    rows: List<ChatRow>,
    scope: ShareScope,
    pickedKeys: Set<String>,
    anchorKey: String?,
): List<ChatRow> = when (scope) {
    ShareScope.Whole -> rows
    ShareScope.Picked -> rows.filter { it.key in pickedKeys }
    ShareScope.Reply -> {
        val anchor = rows.indexOfFirst { it.key == anchorKey && it is AssistantRow }
        if (anchor < 0) {
            emptyList()
        } else {
            val question = (anchor - 1 downTo 0).firstOrNull { rows[it] is UserRow } ?: anchor
            rows.subList(question, anchor + 1)
        }
    }
}

/** 可在多选页里勾选的行：会话里的全部行，顺序与界面一致。 */
fun shareableRows(rows: List<ChatRow>): List<ChatRow> = rows

/**
 * 生成最终要发出去的 Markdown。所有文本都过 [redactShareText] 与
 * [stripMarkdownImages]，包括工具参数、思考过程、通知和标题。
 */
fun buildShareContent(
    rows: List<ChatRow>,
    scope: ShareScope,
    pickedKeys: Set<String> = emptySet(),
    anchorKey: String? = null,
    privacy: SharePrivacy = SharePrivacy(),
    title: String = "",
    hostName: String = "",
    modelName: String? = null,
    now: Long = System.currentTimeMillis(),
): ShareContent {
    val selected = selectShareRows(rows, scope, pickedKeys, anchorKey)
    var redactions = 0

    // 每一段文本都真的过一遍替换，并累计命中次数；图片链接换成 alt 文字，
    // 免得导出文件让接收方去请求一个本机路径或带凭据的 URL。
    fun clean(raw: String): String {
        val (out, hits) = redactShareText(stripMarkdownImages(raw))
        redactions += hits
        return out
    }

    var messages = 0
    var toolCalls = 0
    var images = 0
    val body = ArrayList<String>(selected.size * 3 + 4)

    for (row in selected) {
        when (row) {
            is UserRow -> {
                messages++
                images += row.images.size
                body += "## 我 · ${formatShareTime(row.time)}"
                body += clean(row.text)
                if (privacy.images && row.images.isNotEmpty()) {
                    body += imageNotice(row.images.size)
                }
                body += ""
            }
            is AssistantRow -> {
                messages++
                images += row.images.size
                val head = "## 助手 · ${formatShareTime(row.time)}" +
                    (row.model?.takeIf { it.isNotBlank() }?.let { " · 模型 ${clean(it)}" } ?: "")
                body += head
                if (privacy.thinking && row.reasoning.isNotBlank()) {
                    body += "> **思考过程**"
                    // 移动端 Markdown 渲染器（微信、备忘录等）对 `<details>` 折叠块支持
                    // 不可靠，会把它当裸标签显示。这里用引用块 + 加粗标题，任何渲染器都稳。
                    body += quoteLines(clean(row.reasoning))
                    body += ""
                    body += "> — 思考过程结束 —"
                    body += ""
                }
                body += clean(row.text)
                if (privacy.images && row.images.isNotEmpty()) {
                    body += imageNotice(row.images.size)
                }
                if (row.interrupted) body += "> （本轮被中止，回复可能不完整）"
                body += ""
            }
            is NoticeRow -> {
                body += "> 提示：${clean(row.text)}"
                body += ""
            }
            is ToolRow -> {
                if (privacy.tools) {
                    toolCalls++
                    body += "### 工具 · ${clean(row.name)}（${toolPhaseLabel(row.phase)}）"
                    body += quoteLines(clean(row.arguments))
                    row.output?.takeIf { it.isNotBlank() }?.let {
                        body += "结果："
                        body += quoteLines(clean(it))
                    }
                    row.error?.takeIf { it.isNotBlank() }?.let {
                        body += "错误："
                        body += quoteLines(clean(it))
                    }
                    body += ""
                }
            }
        }
    }

    if (messages == 0 && toolCalls == 0) {
        return ShareContent(
            markdown = "",
            messages = 0,
            toolCalls = 0,
            images = 0,
            redactions = 0,
            selectedKeys = selected.map { it.key },
            scope = scope,
        )
    }

    val meta = ArrayList<String>(5)
    meta += "# ${clean(title).ifBlank { "如意会话" }}"
    meta += ""
    if (hostName.isNotBlank()) meta += "- 主机：${clean(hostName)}"
    // 标成「当前模型」而不是「模型」：历史回复可能用的是别的模型，每条回复头部
    // 才写它自己真实的 model。
    if (!modelName.isNullOrBlank()) meta += "- 当前模型：${clean(modelName)}"
    meta += "- 范围：${when (scope) {
        ShareScope.Reply -> "这条回复"
        ShareScope.Picked -> "选中的 ${selected.size} 条"
        ShareScope.Whole -> "整段对话"
    }}"
    meta += "- 统计：$messages 条消息 · $toolCalls 次工具调用 · $images 张图片" +
        if (privacy.images) "" else "（图片未包含）"
    meta += "- 脱敏：" + if (redactions > 0) "已隐藏 $redactions 处凭据或本机路径" else "未发现需要隐藏的内容"
    meta += "- 导出时间：${formatShareTime(now)}"
    meta += ""
    meta += "---"
    meta += ""

    return ShareContent(
        markdown = (meta + body).joinToString("\n").trimEnd() + "\n",
        messages = messages,
        toolCalls = toolCalls,
        images = images,
        redactions = redactions,
        selectedKeys = selected.map { it.key },
        scope = scope,
    )
}

private fun imageNotice(count: Int): String =
    "> （本条含 $count 张图片；文字与文件导出不含原图）"

private fun quoteLines(text: String): String =
    if (text.isEmpty()) "> （空）" else text.split("\n").joinToString("\n") { "> $it" }

private fun toolPhaseLabel(phase: ToolPhase): String = when (phase) {
    ToolPhase.Running -> "执行中"
    ToolPhase.Succeeded -> "成功"
    ToolPhase.Failed -> "失败"
    ToolPhase.Interrupted -> "已中断"
}

private fun formatShareTime(millis: Long): String =
    if (millis <= 0) "时间未知"
    else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

/* ------------------------------------------------------------------ 脱敏 */

private const val MARK = "•"

private val AUTHORIZATION = Regex(
    """(\b(?:proxy-)?authorization["']?\s*[:=]\s*["']?(?:Bearer|Basic)\s+)([A-Za-z0-9._~+/=-]+)""",
    RegexOption.IGNORE_CASE,
)

/** 聊天里最常见的几种凭据形态：OpenAI / GitHub / AWS / Slack / JWT。 */
private val SECRET_TOKEN = Regex(
    """\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})\b""",
)

private val PRIVATE_KEY_BLOCK = Regex(
    """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""",
)

private val URL_PASSWORD = Regex(
    """(\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s:/@]+:)([^\s/@]+)(@)""",
)

/** `NAME=value` / `"name": "value"`，名字本身说明这是秘密。 */
private val ASSIGNMENT = Regex(
    """(?<![A-Za-z0-9])((?:[A-Za-z0-9]+[_-])*(?:api[_-]?key|apikey|secret|token|password|passwd|access[_-]?key|private[_-]?key|auth[_-]?token|client[_-]?secret)"?'?)(\s*[:=]\s*["']?)([^\s"',;)}]{6,})""",
    RegexOption.IGNORE_CASE,
)

/** 环境变量名或占位符，不是真实值，别把它也打码成一串圆点。 */
private val PLACEHOLDER = Regex("""^(?:\$|<|\{|\*|•|%|xxx)""", RegexOption.IGNORE_CASE)

private val HOME = Regex("""(?<![\w.~-])(/home|/Users)/([A-Za-z0-9._-]+)(?=/|\b)""")
private val WIN_HOME = Regex(
    """(?<!\w)([A-Za-z]):\\Users\\([^\\/\s"<>|:*?]+)""",
    RegexOption.IGNORE_CASE,
)
private val APP_DATA = Regex("""/data/(?:user/\d+|data)/[A-Za-z0-9._]+(?=/|\b)""")

/**
 * 把 [text] 变成可以离开本机的内容，返回处理后的文本与实际替换次数。
 *
 * 规则与参考端 `conversation_redact.dart` 对齐：Authorization 头、私钥块、URL
 * 里的密码、常见 token 形态、形如 `API_KEY=…` 的赋值、家目录与 App 私有目录。
 * 占位符（`$TOKEN`、`<token>`、`***`）保持原样。
 */
fun redactShareText(text: String): Pair<String, Int> {
    if (text.isEmpty()) return "" to 0
    var count = 0
    var out = AUTHORIZATION.replace(text) { m ->
        count++
        m.groupValues[1] + MARK.repeat(6)
    }
    out = PRIVATE_KEY_BLOCK.replace(out) {
        count++
        "[private key removed]"
    }
    out = URL_PASSWORD.replace(out) { m ->
        count++
        m.groupValues[1] + MARK.repeat(6) + m.groupValues[3]
    }
    out = SECRET_TOKEN.replace(out) {
        count++
        MARK.repeat(6)
    }
    out = ASSIGNMENT.replace(out) { m ->
        val value = m.groupValues[3]
        when {
            PLACEHOLDER.containsMatchIn(value) -> m.value
            MARK in value -> m.value
            else -> {
                count++
                val keep = if (value.length > 12) 4 else 0
                m.groupValues[1] + m.groupValues[2] + value.substring(0, keep) + MARK.repeat(6)
            }
        }
    }
    out = HOME.replace(out) {
        count++
        "~"
    }
    out = WIN_HOME.replace(out) {
        count++
        "~"
    }
    out = APP_DATA.replace(out) {
        count++
        "…"
    }
    return out to count
}

private val MD_IMAGE = Regex("""!\[([^\]\n]*)\]\((?:[^()\s]|\([^()\s]*\))+(?:\s+"[^"\n]*")?\)""")
private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})""")

/** Markdown 图片换成 alt 文字；代码块里的 `![]()` 是示例，不能动。 */
fun stripMarkdownImages(markdown: String): String {
    if (!markdown.contains("![")) return markdown
    var fence: String? = null
    return markdown.split("\n").joinToString("\n") { line ->
        if (fence != null) {
            if (line.trimStart().startsWith(fence!!)) fence = null
            line
        } else {
            val open = FENCE.find(line)
            if (open != null) {
                fence = open.groupValues[1].take(3)
                line
            } else {
                MD_IMAGE.replace(line) { m ->
                    val alt = m.groupValues[1].trim()
                    if (alt.isEmpty()) "" else "〔$alt〕"
                }
            }
        }
    }
}
