package com.wangzan101.mika.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.xxt.zl.ai.ruyiremote.ui.RemoteHome

/**
 * 如意远程控制的嵌入页:在 Mika 的「右侧全屏页面」里套 `RemoteHome`,不带额外外壳。
 *
 * `RemoteHome` 自带它自己的导航与返回:登录/电脑/会话/聊天四级栈,返回键先走它自己的
 * BackHandler;当它内部栈空时,由 MikaRoot 上层的 `nav.back()` 退出 `Route.Remote`,
 * 回到对话页。两者按 Compose BackHandler 的注册顺序自然衔接,这里不需要再叠一层。
 *
 * 库不反向依赖宿主,所以 SSO 登录页用外部浏览器打开(优先 Chromium 内核),与独立
 * `ruiyi-remote` App 同策略:避免夸克等第三方内核把 Vue SPA 登录页渲染成白屏。
 * 回跳 `ruyiai://auth2|auth3/callback` 由 `MikaRemoteTrampolineActivity` 转发到
 * `MainActivity`,DeepLinks 再投给这里的 `RemoteHome`。
 */
@Composable
internal fun RemoteScreen(
    callback: String?,
    onCallbackConsumed: () -> Unit,
) {
    val context = LocalContext.current
    RemoteHome(
        callback = callback,
        onCallbackConsumed = onCallbackConsumed,
        openBrowser = { url -> url?.let { openSignInBrowser(context, it) } == true },
    )
}

/** 用外部浏览器打开如意 SSO 登录页;优先 Chromium 内核,退回系统浏览器。 */
private fun openSignInBrowser(context: Context, url: String): Boolean {
    val base = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    for (pkg in arrayOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.microsoft.emmx")) {
        val candidate = Intent(base).setPackage(pkg)
        if (candidate.resolveActivity(context.packageManager) != null) {
            return try {
                context.startActivity(candidate)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }
    return try {
        context.startActivity(base)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}