package com.wangzan101.mika.about

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.text.format.Formatter
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 应用内下载(绑定 Wi-Fi 或走代理)期间的前台服务:切到后台后进程还能继续下载,
 * 通知栏显示百分比与已下载/总大小。下载本体仍在 [ProxiedApkDownload] 里,这里只保活 + 画通知。
 * 系统下载(DownloadManager)有自己的通知,不走这里。
 */
class ApkDownloadForeground : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UpdateNotifications.channel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 之后必须尽快进入前台,先用「等待开始」占位。
        startForeground(
            UpdateNotifications.ID,
            UpdateNotifications.progress(this, ApkDownloadState(DownloadPhase.Pending)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watcher?.isActive == true) return START_NOT_STICKY
        watcher = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (isActive) {
                val state = ApkUpdater.progress(this@ApkDownloadForeground)
                if (state.phase !in listOf(DownloadPhase.Pending, DownloadPhase.Downloading, DownloadPhase.Paused)) {
                    if (state.phase == DownloadPhase.Ready) {
                        nm.notify(UpdateNotifications.READY_ID, UpdateNotifications.ready(this@ApkDownloadForeground, version()))
                    }
                    break
                }
                nm.notify(UpdateNotifications.ID, UpdateNotifications.progress(this@ApkDownloadForeground, state))
                delay(500)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun version(): String = getSharedPreferences("apk-update", MODE_PRIVATE).getString("version", "").orEmpty()

    companion object {
        /** 安卓 12+ 从后台启动前台服务会受限:起不来就退化成仅进程内下载,不打断用户。 */
        internal fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ApkDownloadForeground::class.java))
            } catch (_: Exception) {
            }
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, ApkDownloadForeground::class.java))
        }
    }
}

/** 更新下载的通知:进行中一条(带进度),下好后一条(点了回应用,由应用校验再拉起安装器)。 */
internal object UpdateNotifications {
    const val CHANNEL = "app_update"
    const val ID = 21
    const val READY_ID = 22

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr(R.string.notif_channel_update), NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun progress(context: Context, state: ApkDownloadState): Notification {
        val percent = state.percent
        val text = when {
            percent != null -> "$percent% · " +
                Formatter.formatShortFileSize(context, state.bytes) + " / " +
                Formatter.formatShortFileSize(context, state.total)
            state.phase == DownloadPhase.Pending -> tr(R.string.about_update_waiting)
            else -> tr(R.string.about_update_downloading)
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.about_update_downloading))
            .setContentText(text)
            .setContentIntent(open(context))
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun ready(context: Context, version: String): Notification = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr(R.string.about_update_install))
        .setContentText(version)
        .setContentIntent(open(context))
        .setAutoCancel(true)
        .build()

    fun notifyReady(context: Context) {
        val version = context.getSharedPreferences("apk-update", Context.MODE_PRIVATE)
            .getString("version", "").orEmpty()
        channel(context)
        context.getSystemService(NotificationManager::class.java).notify(READY_ID, ready(context, version))
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
