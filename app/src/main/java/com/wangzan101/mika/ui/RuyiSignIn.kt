package com.wangzan101.mika.ui

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.URLDecoder

/**
 * 如意账号登录的 scheme 回调等待器:登录时 [awaitCode] 挂起等浏览器跳回 `mika://auth/callback`;
 * [onScheme] 由 MainActivity 收到该 scheme 的 Intent 时调用,state 对得上就完成这次等待。
 * 授权码经 URL 片段(`#code=…&state=…`)回传,所以 query 和 fragment 两种都读。
 */
object RuyiSignIn {
    private var pending: CompletableDeferred<Map<String, String>>? = null
    private var expectedState: String? = null

    /** 挂起等一次 scheme 回调,至多 [timeoutMs];超时或没等到返回 null。 */
    suspend fun awaitCode(state: String, timeoutMs: Long): Map<String, String>? {
        val d = CompletableDeferred<Map<String, String>>()
        synchronized(this) {
            pending?.cancel()
            pending = d
            expectedState = state
        }
        return try {
            withTimeout(timeoutMs) { d.await() }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            synchronized(this) {
                if (pending === d) {
                    pending = null
                    expectedState = null
                }
            }
        }
    }

    /** MainActivity 收到 scheme 回调时调用;匹配则完成等待并返回 true,否则 false(不是这次的、或没人等)。 */
    fun onScheme(uri: Uri?): Boolean {
        if (uri?.scheme != "mika" || uri.host != "auth" || uri.path != "/callback") return false
        val raw = uri.query.orEmpty().ifBlank { uri.fragment.orEmpty() }
        val params = parseQuery(raw)
        synchronized(this) {
            val d = pending ?: return false
            if (params["state"] != expectedState) return false
            pending = null
            expectedState = null
            d.complete(params)
            return true
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            runCatching { URLDecoder.decode(k, "UTF-8") }.getOrDefault(k) to
                runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
}
