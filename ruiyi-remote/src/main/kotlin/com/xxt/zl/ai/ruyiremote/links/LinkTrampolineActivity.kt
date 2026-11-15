package com.xxt.zl.ai.ruyiremote.links

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.xxt.zl.ai.ruyiremote.MainActivity

/**
 * Receives the sign-in callback URI (`ruyiai://auth3/callback`, plus the legacy
 * `ruyiai://auth2/callback`) and forwards it into the app's own task.
 *
 * 照抄自 dsh-Go `links/LinkTrampolineActivity.kt`，只改了包名与 MainActivity 的 import。
 * 注释保留原文，因为这些都是真机实测换来的结论：
 *
 * Apps like WeChat start a VIEW activity inside their own task, without
 * FLAG_ACTIVITY_NEW_TASK; aimed at MainActivity that would create a second activity
 * there. Forwarding with NEW_TASK | SINGLE_TOP brings the existing MainActivity forward
 * and delivers the link through onNewIntent, or starts the app with it.
 *
 * CLEAR_TOP was deliberately removed: on some OEM builds (observed on HyperOS 2.0 /
 * Android 14) NEW_TASK | CLEAR_TOP | SINGLE_TOP makes ActivityStarter return
 * START_TASK_TO_FRONT (2) instead of START_DELIVERED_TO_TOP (3) — the task is merely
 * brought to the front and onNewIntent is never called, so the callback link is lost.
 * MainActivity keeps a direct fallback (DeepLinks.deliverFromHost) for the same case.
 */
class LinkTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(intent).apply {
            setClass(this@LinkTrampolineActivity, MainActivity::class.java)
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
