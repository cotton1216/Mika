package com.wangzan101.mika.cap.phone.a11y

import android.view.Gravity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.AppAccessStore
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 用户主动分享的授权浮层;不会先读取应用内容。 */
internal suspend fun requestScreenShare(service: ScreenAccessibility, store: AppAccessStore, pkg: String, label: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        val protection = store.protectUi()
        var window: OverlayWindow? = null
        fun finish(choice: Int) {
            if (!continuation.isActive) return
            val allowed = runCatching { applyShareChoice(choice, pkg, store) }.getOrDefault(false)
            window?.close()
            protection.close()
            continuation.resume(allowed)
        }
        window = service.overlays().open(OverlayPlacement(Gravity.CENTER), onOutside = { finish(0) }) {
            MikaTheme {
                Surface { Column(Modifier.widthIn(max = 340.dp).padding(20.dp)) {
                    Text(stringResource(R.string.appaccess_share_title, label))
                    Text(stringResource(R.string.appaccess_share_body))
                    TextButton({ finish(1) }) { Text(stringResource(R.string.appaccess_share_allow)) }
                    TextButton({ finish(2) }) { Text(stringResource(R.string.appaccess_share_unblock)) }
                    TextButton({ finish(0) }) { Text(stringResource(R.string.appaccess_share_cancel)) }
                } }
            }
        }
        if (window == null) finish(0)
        continuation.invokeOnCancellation { window?.close(); protection.close() }
    }
