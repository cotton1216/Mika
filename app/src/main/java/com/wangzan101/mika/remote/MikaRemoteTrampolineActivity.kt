package com.wangzan101.mika.remote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.wangzan101.mika.ui.MainActivity

/**
 * 如意远程 SSO 登录回跳的落地 Activity。
 *
 * 浏览器登录完把 `ruyiai://auth2|auth3/callback` 回跳到这里(Android 11+ 包可见性
 * 允许外部起 VIEW),再单任务转发给 Mika 唯一的 LAUNCHER —— [MainActivity],
 * 让 [com.xxt.zl.ai.ruyiremote.links.DeepLinks] 在 `onNewIntent` 里把回调投给
 * 右侧的 `RemoteHome`。语义与独立 `ruiyi-remote` 壳的 `LinkTrampolineActivity` 一致。
 *
 * 为什么不能直接用 `FLAG_ACTIVITY_NEW_TASK | CLEAR_TOP | SINGLE_TOP`:HyperOS 2.0 /
 * Android 14 实测会让 ActivityStarter 返回 START_TASK_TO_FRONT 而不是
 * START_DELIVERED_TO_TOP,任务只被提到前台、`onNewIntent` 不派发,回调丢了。
 * 只用 `NEW_TASK | SINGLE_TOP`,MainActivity 内部另有 DeepLinks 兜底去重。
 */
class MikaRemoteTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(intent).apply {
            setClass(this@MikaRemoteTrampolineActivity, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            startActivity(forward)
        } catch (_: Exception) {
            // Nothing to hand over to: the link is dropped.
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}