package com.xxt.zl.ai.ruyiremote.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/*
 * 一条已完成回复的真实元信息：哪个模型答的、花了多久、用了多少 token。
 *
 * 口径全部照抄桌面端的权威实现，不做任何估算：
 *   - 模型：`assistant/message.data.message.source.{provider, model}`
 *     （`ModelMessageSource`，见 ai-server-desktop packages/llm/llm/src/message.ts:24）。
 *     **绝不能**用 `modelSelection.next/lastUsed` 冒充历史回复的模型——那是「下一次用什么」。
 *   - 耗时：同 `turn+step` 的 `step/start.time` → 本条 `assistant/message.time`
 *     （packages/session/session-stats/src/projection.ts:145-148 的 `llmMs`）。
 *     缺起点就是 null，界面上这一段直接不显示，不写 0。
 *   - 首字：`assistant/message.data.stream` 里第一条非空 delta 的 `time`。
 *   - 用量：`assistant/message.data.usage`，即 `TokenUsage`
 *     （packages/llm/llm/src/types.ts:180-196）。三项计数**互不重叠**：
 *     `inputTokens` 只是未命中缓存的输入，缓存命中另记 `cacheReadTokens` /
 *     `cacheWriteTokens`。所以「计费输入」必须三者相加。
 */

/** 一次模型调用的用量；只保留 Host 真的给了的字段。 */
data class TokenUsage(
    /** 未命中缓存的输入 token。 */
    val inputTokens: Long,
    val outputTokens: Long,
    /** Host 给的全量精确总计；缺席时就按互不重叠的三项自行相加（见 [effectiveTotal]）。 */
    val totalTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val reasoningTokens: Long? = null,
) {
    /** 计费输入 = 未缓存 + 读缓存 + 写缓存。 */
    val billedInputTokens: Long
        get() = inputTokens + (cacheReadTokens ?: 0) + (cacheWriteTokens ?: 0)

    /** 优先用 Host 的 `totalTokens`；没有就用三个互不重叠的计数相加，两者都是真实值。 */
    val effectiveTotal: Long
        get() = totalTokens ?: (billedInputTokens + outputTokens)

    /** 有没有缓存命中可讲；没有就不显示缓存角标。 */
    val hasCache: Boolean get() = (cacheReadTokens ?: 0) > 0 || (cacheWriteTokens ?: 0) > 0
}

/**
 * 解析一次调用的用量。
 *
 * `inputTokens` / `outputTokens` 在协议里是必填，缺任意一个就返回 null——
 * 宁可整段不显示，也不拿半个数去凑一个看起来精确的合计。
 */
internal fun parseUsage(value: JsonObject?): TokenUsage? {
    value ?: return null
    val input = value.num("inputTokens") ?: return null
    val output = value.num("outputTokens") ?: return null
    if (input < 0 || output < 0) return null
    return TokenUsage(
        inputTokens = input,
        outputTokens = output,
        totalTokens = value.num("totalTokens")?.takeIf { it >= 0 },
        cacheReadTokens = value.num("cacheReadTokens")?.takeIf { it >= 0 },
        cacheWriteTokens = value.num("cacheWriteTokens")?.takeIf { it >= 0 },
        reasoningTokens = value.num("reasoningTokens")?.takeIf { it >= 0 },
    )
}

/**
 * 首 token 时间（毫秒时间戳）。
 *
 * 只认最直白的那一种记录：`{type:'chunk', chunk:{type:'text-delta'|'reasoning-delta'|…}, time}`。
 * 桌面端还会把多条 delta 打包成 `text-chunks` 这种紧凑记录、按成员重建时间，
 * 这里不重复实现那套重建逻辑——认不出来就不给数字，界面上少一行而已。
 */
internal fun firstTokenTime(stream: JsonArray?): Long? {
    stream ?: return null
    for (record in stream) {
        val map = record as? JsonObject ?: continue
        if (map.str("type") != "chunk") continue
        val chunk = map.obj("chunk") ?: continue
        val meaningful = when (chunk.str("type")) {
            "text-delta", "reasoning-delta" -> chunk.str("text")?.isNotEmpty() == true
            "tool-call-delta" ->
                chunk.str("argumentsDelta")?.isNotEmpty() == true || chunk.str("name") != null
            else -> false
        }
        if (!meaningful) continue
        return map.long("time")
    }
    return null
}

/** `3.4K` / `1.2M` / `857`：千进位缩写成一位小数，尾部 `.0` 去掉。 */
fun formatTokenCount(tokens: Long): String {
    if (tokens < 0) return ""
    return when {
        tokens < 1_000 -> tokens.toString()
        tokens < 1_000_000 -> trimZero(tokens / 1_000.0) + "K"
        else -> trimZero(tokens / 1_000_000.0) + "M"
    }
}

/** `1.2 秒` / `17 秒` / `1 分 20 秒`；`ms` 为 null 时返回空串（调用处据此整段不渲染）。 */
fun formatElapsed(ms: Long?): String {
    if (ms == null || ms < 0) return ""
    if (ms < 60_000) return trimZero(ms / 1000.0) + " 秒"
    val totalSeconds = (ms + 500) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (seconds == 0L) "$minutes 分" else "$minutes 分 $seconds 秒"
}

private fun trimZero(value: Double): String {
    val text = String.format(Locale.US, "%.1f", value)
    return if (text.endsWith(".0")) text.dropLast(2) else text
}
