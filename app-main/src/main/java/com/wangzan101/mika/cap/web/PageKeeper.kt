package com.wangzan101.mika.cap.web

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 进程里那一份「任务结束后留下的网页」:任务结束时([WebStack.close])把模型用 web_keep_open 标过的那一页从任务的标签池里拿出来,
 * 连同它挂着的那块后台网页显示一起交到这里——页面照旧挂在任务期间那块显示上,和任务期间一样看得见、照常运行,不另做别的手脚。
 * 全局至多一页([KeptPageSlot]),[KEEP_OPEN_HOURS][com.wangzan101.mika.core.cap.web.KEEP_OPEN_HOURS] 小时后自动关;
 * 内存紧张被系统要求释放、渲染进程崩了或被系统收回、用户关掉、留了另一页时也关。关的时候销毁标签、放掉显示、Cookie 写盘,
 * 并交给 [onClosed](在来源对话那一轮记下原因)。
 * 留着的那一页不在任何任务的标签池里:之后的任务列不到、关不到它;它算进各任务共用的标签总数(见 [TabBudget]),但不会被腾位置关掉。
 * 进程靠任务服务的前台通知撑着(见 TaskService)。会动到 WebView 的都在主线程;[close] 任意线程可调。
 */
class PageKeeper(
    private val context: Context,
    private val budget: TabBudget,
    clock: () -> Long = System::currentTimeMillis,
) : TabBudget.Member {
    /** 撑着页面的标签和它挂着的显示。 */
    class Held(val tab: WebTab, val host: WebHost)

    private val main = Handler(Looper.getMainLooper())

    /** 关掉一页之后(主线程外也可能调):来源对话那一轮记下原因。由 App 接上。 */
    @Volatile var onClosed: (KeptPage, KeptPageClose) -> Unit = { _, _ -> }

    private val slot: KeptPageSlot<Held> = KeptPageSlot(clock, release = ::release, onClosed = { p, r ->
        Log.i(TAG, "kept page ${p.id} closed: ${r.wire}")
        main.removeCallbacks(expiry)
        onClosed(p, r)
    })

    /** 此刻留着的那一页;没有为 null。 */
    val page: StateFlow<KeptPage?> get() = slot.page

    private val expiry: Runnable = object : Runnable {
        override fun run() {
            if (slot.expireIfDue()) return
            // 系统深睡时这个计时会停:每隔一会儿按墙上时钟核一次,醒来过了点就关
            slot.remainingMs()?.let { main.postDelayed(this, it.coerceIn(1_000L, CHECK_MS)) }
        }
    }

    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) close(KeptPageClose.Memory)
        }
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    /** 留着的页面上发生的事:只管崩溃;弹窗、下载、跳 App 一律不接(没有任务在看它)。 */
    private inner class Listener(val id: Long) : WebTab.Listener {
        override fun onCrashed(tab: WebTab) {
            // 在 WebView 自己的回调之外关
            main.post { slot.closeIf(id, if (tab.reclaimed) KeptPageClose.Memory else KeptPageClose.Crashed) }
        }
    }

    init {
        context.applicationContext.registerComponentCallbacks(memory)
        budget.join(this)
    }

    /**
     * 收下 [tab](已从任务的标签池里拿出来)和它的显示 [host],留在后台接着运行;此刻留着的另一页先关掉。
     * 标签已关、崩了或显示已关时收不下,返回 null,调用方自己销毁。主线程。
     */
    fun keep(tab: WebTab, host: WebHost, origin: KeptOrigin): KeptPage? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PageKeeper.keep must run on the main thread" }
        if (tab.destroyed || tab.crashed || host.closed) return null
        val url = tab.view.url
        val site = url?.let(WebUrlPolicy::host) ?: url.orEmpty()
        // 挂回它那块显示上(任务结束时它可能不是挂着的那一个);页面照旧认为自己可见
        host.attach(tab)
        runCatching { WebTab.cookieManager().flush() }
        val p = slot.keep(Held(tab, host), origin, site, url, tab.view.title)
        tab.listener = Listener(p.id)
        main.removeCallbacks(expiry)
        main.postDelayed(expiry, (p.closesAt - p.keptAt).coerceIn(1_000L, CHECK_MS))
        Log.i(TAG, "kept page ${p.id} on $site from conversation ${p.conversationId}")
        return p
    }

    /** 关掉留着的那一页(用户点了关闭、通知上的按钮、内存紧张等);没有留着的返回 false。任意线程。 */
    fun close(reason: KeptPageClose): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (slot.page.value == null) return false
            main.post { slot.close(reason) }
            return true
        }
        return slot.close(reason)
    }

    /** 还剩多久自动关(毫秒);没有留着的为 null。 */
    fun remainingMs(): Long? = slot.remainingMs()

    /** 留着的那个标签(测试和真机验收看页面还在不在跑);没有为 null。 */
    fun keptTab(): WebTab? = slot.handle()?.tab

    /** 运行 [runId] 那一轮要记下的留页:(站点, 关掉的原因),见 [KeptPageSlot.recordFor]。 */
    fun recordFor(runId: String?): Pair<String, String?>? = slot.recordFor(runId)

    /** 留着的那一页的实时画面,每 [FRAME_MS] 取一帧(和任务期间画中画同一个来源:它那块显示的最新一帧);没有画面时发 null。 */
    fun frames(): Flow<Bitmap?> = flow {
        while (true) {
            emit(slot.handle()?.host?.takeIf { !it.closed }?.latestFrame())
            delay(FRAME_MS)
        }
    }.flowOn(Dispatchers.IO)

    // ---------- 全局上限 ----------

    override fun tabCount(): Int = if (slot.page.value != null) 1 else 0

    /** 留着的那一页不给腾位置关掉:用户要它接着开着。 */
    override fun oldestSpare(except: String?): Pair<String, Long>? = null

    override fun evictForBudget(tabId: String) = Unit

    private fun release(h: Held) {
        val run = Runnable {
            runCatching { WebTab.cookieManager().flush() }
            runCatching { h.tab.destroy() }
            runCatching { h.host.close() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    private companion object {
        const val TAG = "MikaPageKeeper"
        /** 多久按墙上时钟核一次到没到点。 */
        const val CHECK_MS = 60_000L
        const val FRAME_MS = 500L
    }
}
