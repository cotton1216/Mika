package com.wangzan101.mika.voicechat

/**
 * 插话语义分类。dsh-Go 原版分三态：
 *   backchannel — 用户只是在听（"嗯"、"好的"），让 AI 继续
 *   stop — 用户想打断
 *   speech — 用户有新问题
 *
 * MVP 简化版：只分 stop（音量突然拉高）和 speech（有新内容），backchannel 归 speech。
 * Phase 2 再接入完整的语义分析（可以让 VolcEngine 的转写结果做关键词匹配）。
 */
object OverWords {
    /** MVP 简化：如果用户转写文本长度 > 2 就当 speech，否则当 stop */
    fun classify(transcript: String): Class {
        val t = transcript.trim()
        if (t.isBlank()) return Class.STOP
        return if (t.length > 2) Class.SPEECH else Class.STOP
    }

    enum class Class {
        /** 用户在打断 */
        STOP,
        /** 用户在说话（新问题） */
        SPEECH,
    }
}
