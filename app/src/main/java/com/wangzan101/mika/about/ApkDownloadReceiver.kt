package com.wangzan101.mika.about

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 系统下载(DownloadManager)下完后补一条「已下载」通知:系统那条只在下载中显示,
 * 完成的通知点开会直接打开安装包、绕过应用校验,所以由应用自己发一条点了只回应用的通知。
 */
class ApkDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val prefs = context.getSharedPreferences("apk-update", Context.MODE_PRIVATE)
        if (id == -1L || prefs.getBoolean("viaApp", false) || prefs.getLong("id", -1) != id) return
        UpdateNotifications.notifyReady(context)
    }
}
