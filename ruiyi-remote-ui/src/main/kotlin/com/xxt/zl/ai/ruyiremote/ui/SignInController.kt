package com.xxt.zl.ai.ruyiremote.ui

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xxt.zl.ai.ruyiremote.auth.RuyiAccount
import com.xxt.zl.ai.ruyiremote.auth.RuyiAttempt
import com.xxt.zl.ai.ruyiremote.auth.RuyiAuth
import com.xxt.zl.ai.ruyiremote.auth.SignInException
import com.xxt.zl.ai.ruyiremote.auth.SignInFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URLDecoder

/** 登录流程对外可见的阶段。 */
sealed interface SignInPhase {
    data object Idle : SignInPhase
    data object OpeningBrowser : SignInPhase
    data object WaitingForBrowser : SignInPhase
    data object Exchanging : SignInPhase
    data class SignedIn(val account: RuyiAccount) : SignInPhase
    data class Failed(val failure: SignInFailure, val code: String?) : SignInPhase
}

/**
 * 一次如意登录的完整流程：生成 PKCE → 打开授权页 → 等 scheme 回跳 → 交中继换票。
 *
 * 与 mika `signInWithRuyi` 同一顺序，只是把「等回调」从挂起函数换成了
 * [DeepLinks] 推送 + 显式阶段，因为这里是 Android 原生而不是协程等待器。
 * 回调里的授权码在 URL **片段**里，所以 query 与 fragment 都要读。
 *
 * 换票那一跳打的是**中继**而不是 AI-Server，所以需要 [relayBase]：它取用户当前配置的
 * 中继地址（`RemoteAccount.serverUrl`），而不是一个编译期常量。
 */
class SignInController(
    private val auth: RuyiAuth,
    private val openBrowser: (String) -> Boolean,
    private val relayBase: () -> String,
    private val scope: CoroutineScope,
) {
    var phase: SignInPhase by mutableStateOf(SignInPhase.Idle)
        private set

    private var attempt: RuyiAttempt? = null

    /** 是否正在等浏览器回来（决定要不要消费回跳）。 */
    val awaitingCallback: Boolean get() = phase is SignInPhase.WaitingForBrowser

    fun start() {
        if (phase is SignInPhase.WaitingForBrowser || phase is SignInPhase.Exchanging) return
        val next = auth.newAttempt()
        attempt = next
        phase = SignInPhase.OpeningBrowser
        val authorizeUrl = auth.authorizeUrl(next)
        Log.i("RuyiSignIn", "发起授权：host=${Uri.parse(authorizeUrl).host}")
        val opened: Boolean = runCatching { openBrowser(authorizeUrl) }.getOrElse { false }
        phase = if (opened) SignInPhase.WaitingForBrowser else SignInPhase.Failed(SignInFailure.Rejected, "no_browser")
    }

    fun cancel() {
        attempt = null
        phase = SignInPhase.Idle
    }

    /** 收到 scheme 回跳。不是这次登录的就忽略。 */
    fun onCallback(rawUri: String) {
        val expected = attempt
        if (expected == null || !awaitingCallback) {
            Log.w("RuyiSignIn", "回跳被忽略：有待处理授权=${expected != null}，阶段=${phase::class.simpleName}")
            return
        }
        val params = parseParams(Uri.parse(rawUri)) ?: run {
            Log.w("RuyiSignIn", "回跳被忽略：没有查询参数")
            return
        }
        if (!auth.accepts(params, expected)) {
            Log.w("RuyiSignIn", "回跳被忽略：state 不匹配或无有效授权结果")
            return
        }
        val code = try {
            auth.readCode(params, expected)
        } catch (e: SignInException) {
            Log.w("RuyiSignIn", "回跳校验失败：${e.code}")
            phase = SignInPhase.Failed(e.failure, e.code)
            return
        }
        phase = SignInPhase.Exchanging
        scope.launch {
            try {
                val account = auth.exchange(code, expected, relayBase())
                attempt = null
                phase = SignInPhase.SignedIn(account)
                Log.i("RuyiSignIn", "中继账号换票成功")
            } catch (e: SignInException) {
                Log.w("RuyiSignIn", "中继账号换票失败：${e.code}")
                phase = SignInPhase.Failed(e.failure, e.code)
            }
        }
    }

    /** 授权码可能在 fragment（`#code=…`）而不是 query，两边都取。 */
    private fun parseParams(uri: Uri): Map<String, String>? {
        val raw = uri.query?.takeIf { it.isNotBlank() } ?: uri.fragment?.takeIf { it.isNotBlank() }
        ?: return null
        return raw.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(key) to decode(value)
            }
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
