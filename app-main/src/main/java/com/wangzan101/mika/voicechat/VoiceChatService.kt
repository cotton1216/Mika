package com.wangzan101.mika.voicechat

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
import com.wangzan101.mika.ui.MainActivity

/**
 * 语音通话期间的前台服务:只为了让麦克风在如意 AI 离开前台后还能继续采集。
 *
 * Android 12+ 规定后台应用不得访问麦克风,除非有一个 foregroundServiceType=microphone 的
 * 前台服务在跑。没有它的话,切到别的 App 的瞬间采集就被系统掐断,会话随即超时断流,
 * 表现就是「AI 突然不出声、浮窗也跟着消失」。
 *
 * 本身不持有通话状态:通话由 [VoiceChatController] 管,这里只负责挂一个常驻通知保住采集权限。
 */
class VoiceChatService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = notification()
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
        // 拿不到前台资格(通知被关等)时直接收摊,通话仍可在前台进行,只是没有后台采集
        if (!promoted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(com.wangzan101.mika.R.string.app_name))
            .setContentText("语音通话进行中")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "语音通话", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL = "mika_voice_chat"
        private const val NOTIFICATION_ID = 90_219

        /** 通话开始:挂上前台服务,让麦克风在后台不被系统收回。失败时静默降级,不影响前台通话。 */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, VoiceChatService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }
        }

        /** 通话结束:撤掉前台服务与通知。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceChatService::class.java)) }
        }
    }
}
