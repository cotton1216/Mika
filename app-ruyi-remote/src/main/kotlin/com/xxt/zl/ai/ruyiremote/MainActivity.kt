package com.xxt.zl.ai.ruyiremote

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xxt.zl.ai.ruyiremote.links.DeepLinks
import com.xxt.zl.ai.ruyiremote.ui.RemoteHome

class MainActivity : ComponentActivity() {

    /** 最近一次到达的回跳 URI，交给 Compose 层的登录流程消费。 */
    private var pendingCallback by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeepLinks.takeLaunch(intent, DeepLinks.isConsumed(intent))
        setContent {
            RemoteHome(
                callback = pendingCallback,
                onCallbackConsumed = { pendingCallback = null },
                openBrowser = { url -> url?.let(::openSignInBrowser) == true },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 两条路都走一遍：正常情况下 NewIntentListener 已在上面投递，
        // DeepLinks 内部按 intent 身份去重，所以不会重复。
        DeepLinks.take(intent)
        DeepLinks.deliverFromHost(intent)
    }

    /**
     * 如意的 SSO 登录页是 Vue SPA，夸克等第三方内核会把它渲染成纯白页
     * （真机实测：同一个 authorize 地址在 Chrome 正常显示登录表单）。
     *
     * 优先交给 Chromium 内核的浏览器，仍是外部系统浏览器而非 WebView，
     * OIDC 的安全属性不变；回跳 `ruyiai://auth3/callback` 走 LinkTrampolineActivity。
     * 与 dsh-Go `MainActivity.openSignInBrowser` 同一套策略。
     */
    fun openSignInBrowser(url: String): Boolean {
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (pkg in arrayOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.microsoft.emmx")) {
            val candidate = Intent(base).setPackage(pkg)
            if (candidate.resolveActivity(packageManager) != null) {
                return try {
                    startActivity(candidate)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
        }
        return try {
            startActivity(base)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
