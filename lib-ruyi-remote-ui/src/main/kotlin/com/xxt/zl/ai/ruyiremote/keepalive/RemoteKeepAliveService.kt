package com.xxt.zl.ai.ruyiremote.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Keeps the relay connection alive while the app is in the background.
 *
 * 自 ruyi-remote-native 移植：为让库不反向依赖宿主 Activity，把通知 Intent 改为
 * 唤醒宿主启动页、把 `R.string.*` 文案改为内联字符串。保活语义一字未动。原因保留原文：
 *
 * 手机驱动远程主机时不跑本地引擎，没有任何东西托住它的进程，Android 就能在 App
 * 退到后台几秒后冻结或杀掉它。中继 socket 随之静默断掉，桌面端的设备列表里这台
 * 手机就显示离线。
 *
 * The service owns no connection itself — the Kotlin relay client does. All it does
 * is (a) keep the process in the foreground service state so the socket is not
 * frozen, and (b) hold a partial wake lock so the CPU does not sleep mid-write.
 * It stops as soon as no remote host is in use, so the notification never
 * lingers for someone who turned remote access off.
 */
class RemoteKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        running = true
        foreground(buildNotification())
        acquireWakeLock()
        // Not sticky: a killed process comes back through the client side, which
        // knows whether a remote host is actually still in use.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun foreground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                KEEPALIVE_CHANNEL_LABEL,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = KEEPALIVE_CHANNEL_DESC
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        // 库不反向依赖宿主 Activity：改用启动器 Intent 回到宿主主界面。
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        var open: PendingIntent? = null
        if (launchIntent != null) {
            open = PendingIntent.getActivity(
                this,
                0,
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(KEEPALIVE_TITLE)
            .setContentText(KEEPALIVE_TEXT)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "dsh_remote_keepalive"
        private const val NOTIFICATION_ID = 0x4453_52
        private const val WAKE_LOCK_TAG = "dsh:remote-keepalive"
        private const val KEEPALIVE_CHANNEL_LABEL = "远程连接"
        private const val KEEPALIVE_CHANNEL_DESC = "手机与桌面保持连接时显示的前台通知渠道"
        private const val KEEPALIVE_TITLE = "如意远程运行中"
        private const val KEEPALIVE_TEXT = "正在保持与您电脑的连接"
        const val ACTION_START = "com.xxt.zl.ai.ruyiremote.keepalive.KEEPALIVE_START"
        const val ACTION_STOP = "com.xxt.zl.ai.ruyiremote.keepalive.KEEPALIVE_STOP"

        /**
         * True while the foreground service holds the process up.
         *
         * 这个值**不是同步生效**的：`start()` 只是把 `startForegroundService`
         * 发出去，`running` 要等 `onStartCommand` 真正跑起来才变 true。所以界面
         * 不能在 `start()` 之后立刻读回它 —— 那一刻必然还是 false，开关会自己
         * 弹回去。要跟着状态变就用 [observe]。
         */
        @Volatile
        var running: Boolean = false
            private set(value) {
                if (field == value) return
                field = value
                listeners.forEach { listener -> runCatching { listener(value) } }
            }

        private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

        /** 订阅开关的真实状态。注册时立即回调一次当前值，返回注销函数。 */
        fun observe(listener: (Boolean) -> Unit): () -> Unit {
            listeners.add(listener)
            listener(running)
            return { listeners.remove(listener) }
        }

        fun start(context: Context) {
            if (running) return
            val intent = Intent(context, RemoteKeepAliveService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            // 不判 `running`：on 之后立刻 off 时 onStartCommand 可能还没来得及跑，
            // 此时 running 仍是 false，早退会让服务停不下来。ACTION_STOP 打到没在
            // 跑的服务上只是建起来再 stopSelf()，代价可以忽略。
            runCatching {
                context.startService(
                    Intent(context, RemoteKeepAliveService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
