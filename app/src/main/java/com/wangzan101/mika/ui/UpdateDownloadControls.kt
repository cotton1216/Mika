package com.wangzan101.mika.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.about.ApkDownloadState
import com.wangzan101.mika.about.ApkRejected
import com.wangzan101.mika.about.DownloadPhase
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UpdateDownloadControls(release: ReleaseInfo) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val updater = app.apkUpdater
    val scope = rememberCoroutineScope()
    var state by remember(release.apkUrl) { mutableStateOf(ApkDownloadState()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApkRejected) {
                app.automaticUpdates.dismiss(release)
                error = when (e.reason) {
                    ApkRejected.Reason.Corrupt -> R.string.about_update_bad_file
                    ApkRejected.Reason.NotNewer -> R.string.about_update_not_newer
                    ApkRejected.Reason.Signer -> R.string.about_update_bad_signer
                }
            } catch (_: Exception) {
                error = R.string.about_update_error
            } finally {
                busy = false
                refresh++
            }
        }
    }

    fun install() = run {
        val intent = withContext(Dispatchers.IO) { updater.installIntent(release) }
        context.startActivity(intent)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
    }

    // Android 13+ 通知权限被拒时,后台下载/前台服务的进度通知都看不到;下载前先申请,便于用户打开通知栏跟踪进度。
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun ensureNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(release.apkUrl, refresh) {
        try {
            do {
                state = withContext(Dispatchers.IO) { updater.state(release) }
                if (state.phase !in listOf(DownloadPhase.Pending, DownloadPhase.Downloading, DownloadPhase.Paused)) break
                delay(1000)
            } while (true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = R.string.about_update_error
        }
    }

    UpdateDownloadActions(state, busy, error,
        onDownload = {
            ensureNotification()
            run { withContext(Dispatchers.IO) { updater.download(release) } }
        },
        onCancel = {
            run {
                withContext(Dispatchers.IO) { updater.cancel() }
                app.automaticUpdates.dismiss(release)
            }
        },
        onInstall = {
            if (context.packageManager.canRequestPackageInstalls()) install()
            else run {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
        },
    )
}

@Composable
internal fun UpdateDownloadActions(
    state: ApkDownloadState,
    busy: Boolean,
    /** 上次操作没成时的说明;为空时显示安装提示。 */
    error: Int?,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val active = state.phase in listOf(DownloadPhase.Pending, DownloadPhase.Downloading, DownloadPhase.Paused)
    val text = when {
        busy -> stringResource(R.string.about_update_working)
        state.phase == DownloadPhase.Paused -> stringResource(R.string.about_update_paused)
        state.phase == DownloadPhase.Pending -> stringResource(R.string.about_update_waiting)
        active -> state.percent?.let { stringResource(R.string.about_update_progress, it) }
            ?: stringResource(R.string.about_update_downloading)
        state.phase == DownloadPhase.Ready -> stringResource(R.string.about_update_install)
        state.phase == DownloadPhase.Failed -> stringResource(R.string.about_update_retry)
        else -> stringResource(R.string.about_update_download_in_app)
    }
    MikaButton(text, if (state.phase == DownloadPhase.Ready) onInstall else onDownload,
        Modifier.fillMaxWidth().padding(top = 14.dp), enabled = !busy && !active)
    if (active || state.phase == DownloadPhase.Ready) {
        MikaButton(stringResource(R.string.about_update_cancel), onCancel,
            Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Tonal, enabled = !busy)
    }
    // 系统还在排队(Pending)时拿不到总大小,只有进度条会很可疑,所以只等有真实字节数再画。
    val percent = state.percent
    if (active && percent != null) {
        val context = LocalContext.current
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp)
            .clip(RoundedCornerShape(2.dp)).background(MikaTheme.colors.ink3.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth((percent / 100f).coerceIn(0f, 1f)).fillMaxHeight()
                .background(MikaTheme.colors.accent))
        }
        Text("$percent% · ${Formatter.formatShortFileSize(context, state.bytes)} / " +
            Formatter.formatShortFileSize(context, state.total),
            Modifier.padding(top = 6.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
    }
    val note = error ?: if (state.phase == DownloadPhase.Failed) R.string.about_update_error else R.string.about_update_install_hint
    Text(stringResource(note),
        Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
}
