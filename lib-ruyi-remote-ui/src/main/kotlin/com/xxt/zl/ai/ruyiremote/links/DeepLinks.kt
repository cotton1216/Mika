package com.xxt.zl.ai.ruyiremote.links

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 登录回跳链路的投递器。语义照抄 dsh-Go 的 `links/LinksPlugin.kt`，但去掉了
 * MethodChannel —— 这里没有 Flutter/Dart 一侧，订阅者就是 Compose 层或登录流程。
 *
 * 原版 Why，逐条保留（都是真机踩出来的）：
 *
 * 1. **ready 之前到达的链接要排队**。冷启动时 `onCreate` 拿到的 launch intent 早于
 *    UI 订阅，直接丢弃就会让"点了浏览器里的登录确认却什么也没发生"复现。
 * 2. **launch intent 只取一次**。取走后写 [EXTRA_TAKEN] 标记，配置变更重建
 *    Activity 时不会二次投递。
 * 3. **从最近任务重开的 intent 要丢弃**。Android 会重放 intent 并带
 *    `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`，那不是一次新的登录回调。
 * 4. **[deliverFromHost] 兜底**。部分 OEM（HyperOS 2.0 / Android 14 实测）的
 *    ActivityStarter 只把任务提到前台而不派发 `onNewIntent`，所以 MainActivity
 *    在 `onNewIntent` 里再直接投一次；[lastIntentId] 保证不会投两遍。
 */
object DeepLinks {
    private const val TAG = "RuyiDeepLinks"

    /**
     * 如意登录的 redirect_uri scheme。host 只用来区分版本（`auth2` 是旧回调，
     * `auth3` 是现用回调），这里按 scheme 放行、由 [SignInController] 用 state 认领。
     */
    const val SCHEME = "ruyiai"

    private const val EXTRA_TAKEN = "com.xxt.zl.ai.ruyiremote.links.TAKEN"

    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<String>()

    @Volatile
    private var listener: ((String) -> Unit)? = null

    /** [MainActivity.onNewIntent] 处理过的 intent 身份，供兜底去重。 */
    private var lastIntentId = 0

    /**
     * 订阅回跳 URI。订阅时会把 [listen] 之前到达、仍在排队的链接按序补发。
     * 必须在主线程调用（Activity 生命周期或 Compose 的 LaunchedEffect 都是）。
     */
    fun listen(onLink: (String) -> Unit) {
        listener = onLink
        val held = pending.toList()
        pending.clear()
        Log.d(TAG, "listen: flushing ${held.size} held link(s)")
        for (uri in held) onLink(uri)
    }

    fun stopListening() {
        listener = null
    }

    /** 冷启动：把 launch intent 里的回跳取出来（只取一次）。 */
    fun takeLaunch(intent: Intent?, consumed: Boolean) {
        if (intent == null || consumed) return
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
        if (take(intent)) intent.putExtra(EXTRA_TAKEN, true)
    }

    fun isConsumed(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_TAKEN, false) == true

    /**
     * OEM 不派发 NewIntentListener 时的兜底。MainActivity 在 `onNewIntent` 里、
     * `super` 之后调用它；[lastIntentId] 保证同一次到达只投一遍。
     */
    fun deliverFromHost(intent: Intent?): Boolean {
        if (intent == null) return false
        if (System.identityHashCode(intent) == lastIntentId) return false
        Log.d(TAG, "deliverFromHost: fallback for ${intent.action} ${intent.data}")
        return take(intent)
    }

    /** 处理 [intent] 携带的回跳；不是登录回调就返回 false。 */
    fun take(intent: Intent): Boolean {
        lastIntentId = System.identityHashCode(intent)
        if (intent.action != Intent.ACTION_VIEW) return false
        val data = intent.data ?: return false
        if (!data.scheme.equals(SCHEME, ignoreCase = true)) return false
        deliver(data.toString())
        return true
    }

    private fun deliver(uri: String) {
        // 回调一律切回主线程：Intent 可能从 trampoline 的 onCreate 到达，
        // 而订阅者在 Compose 主线程上。
        main.post {
            val target = listener
            if (target != null) {
                Log.d(TAG, "deliver $uri")
                target(uri)
            } else {
                Log.d(TAG, "hold $uri (no listener yet)")
                pending.addLast(uri)
            }
        }
    }
}
