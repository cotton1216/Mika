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
 * BackHandler;当它内部栈空(停在「我的电脑」)时,通过 `onExit` 明确退回如愿的对话页,
 * 顶栏同时给出一个「返回如愿」箭头 —— 不用用户靠系统返回键去猜。
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
    /** 退出远程控制、回到如愿。远程根页（我的电脑）会给一个显式「返回如愿」箭头。 */
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    RemoteHome(
        callback = callback,
        onCallbackConsumed = onCallbackConsumed,
        openBrowser = { url -> url?.let { openSignInBrowser(context, it) } == true },
        // 一个登录就够了：远程控制的设备在如愿登录时已经注册好，这里不再摆第二个
        // 如意账号入口。真走到登录页也只留配对码与账号密码。
        showSso = false,
        // 远程控制是如愿里的一页，得能明确退回去，而不是靠系统返回键猜。
        onExit = onBack,
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