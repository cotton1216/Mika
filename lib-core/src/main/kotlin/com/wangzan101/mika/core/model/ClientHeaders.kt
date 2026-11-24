package com.wangzan101.mika.core.model

import okhttp3.Request
import java.util.UUID

/**
 * 一段对话对外的会话号:同一段对话的每个请求(主模型、看图、轻量、起标题、核查、意图判断、停止后的交代)都用同一个,
 * 服务商据此把请求路由到一处、复用提示词缓存。由对话 id 推出(UUID v3,不可逆),不把对话 id 本身发出去。
 * 不属于任何对话的请求(连接测试、列模型)每次用 [fresh] 的新号。
 */
@JvmInline
value class ModelSession(val id: String) {
    companion object {
        fun of(conversationId: String): ModelSession =
            ModelSession(UUID.nameUUIDFromBytes("mika-conversation:$conversationId".toByteArray()).toString())

        fun fresh(): ModelSession = ModelSession(UUID.randomUUID().toString())
    }
}

/** 发给模型服务的每个请求都带的头;Key 的头由各适配器自己加。 */
object ClientHeaders {
    /**
     * [userAgent] 标明是哪个版本(由 App 给,core 不写版本号);[sessionHeader] 是服务商声明要放会话号的头名,
     * 没声明(null 或空)就不发会话号。
     *
     * 头值只能是可见 ASCII:OkHttp 遇到中文直接抛 `IllegalArgumentException: Unexpected char 0x....`,
     * 请求根本发不出去(品牌中文名只用在界面里)。这里再兜一层,调用方传了中文也不至于让整次模型调用失败。
     */
    fun of(userAgent: String, sessionHeader: String?, session: ModelSession): Map<String, String> = buildMap {
        put("User-Agent", asciiValue(userAgent))
        // 头名只能是 ASCII token(字母数字和 - _ . 之类);配置里写了中文头名就当没配。
        if (!sessionHeader.isNullOrBlank() && sessionHeader.all { isTokenChar(it) }) {
            put(sessionHeader.trim(), session.id)
        }
    }

    /** 去掉非可见 ASCII 与首尾的 `/`,剩下的空串说明全是中文,退回 `Yuan`。 */
    internal fun asciiValue(value: String): String =
        value.filter { it.code in 0x20..0x7e }.trim('/').trim().ifBlank { "Yuan" }

    private fun isTokenChar(c: Char): Boolean =
        c.code in 0x21..0x7e && c !in "()<>@,;:\\\"/[]?={} \t"
}

internal fun Request.Builder.withHeaders(headers: Map<String, String>): Request.Builder =
    apply { headers.forEach { (k, v) -> header(k, v) } }
