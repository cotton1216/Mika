package com.wangzan101.mika.cap.web

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.cap.web.TabInfo
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.Closeable
import com.wangzan101.mika.core.cap.web.ExternalLink as Link
import com.wangzan101.mika.cap.web.cdp.WebDevtools

/** 标签池里发生的事:新开、关掉(原因)、崩溃、拦下的跳 App 链接、内存回收后切回时重新加载。 */
sealed interface PoolEvent {
    data class NewTab(val tabId: String, val taskId: String, val fromPopup: Boolean) : PoolEvent
    /**
     * [reason]:closed 主动关、limit 超出上限关掉最久没用的、task_end 任务结束、popup_other_site 弹出窗口要去别的网站;
     * 借来的标签([WebTabPool.attachExternal])还回去时是 returned,任务结束时留在后台([WebTabPool.release])是 kept(这两种标签本身没关)。
     */
    data class Closed(val tabId: String, val reason: String) : PoolEvent
    data class Crashed(val tabId: String) : PoolEvent
    data class ExternalLink(val tabId: String, val link: Link) : PoolEvent
    data class Reloaded(val tabId: String) : PoolEvent
}

/**
 * 网页标签池:每个任务至多 [perTask] 个标签;所有任务合起来至多 [budget] 的上限(跨任务共用一份);
 * 超出时关掉本任务最久没用的那个,本任务没有可关的才关别的任务里最久没用的。
 * 同一时间只有最近打开或切到的那个标签挂在 [host] 上绘制,其余在后台照常运行(页面看到的是后台标签)。
 * 页面弹出的新窗口只在用户点了(有手势)时才在池里开新标签,并且只能是同一网站;跳 App 的链接不跳,发 [PoolEvent.ExternalLink]。
 * 渲染进程崩溃:销毁该标签,发 [PoolEvent.Crashed],下一次打开网址开新标签。
 * 系统内存紧张时关掉后台标签只记下地址,切回时重新加载;页面加载完、任务结束、App 进入后台时把 Cookie 写盘,登录不丢。
 * 借来的标签([attachExternal],用户在浏览器页上看着的那一个):当成任务的当前标签用,但不归池管——不挂到 [host] 上(它在用户的界面里),
 * 不算上限、不被腾位置或内存回收关掉,池也不接它上面的事(下载、弹窗、登录探查由它原来的主人管);关它、任务结束时只是还回去([detachExternal]),不销毁。
 *
 * 会动到 WebView 的方法([open]、[switchTo]、[close]、[closeAll]、[list]、[onTrimMemory])都在主线程调用;
 * [current]、[isLive]、[crashedTab] 任意线程可调。
 */
class WebTabPool(
    private val context: Context,
    private val host: WebHost,
    private val perTask: Int = 4,
    /** 各任务共用的标签总数上限;默认是进程里那一份。 */
    private val budget: TabBudget = WebOwner.of(context).tabBudget,
    private val assetLoaders: List<WebViewAssetLoader> = emptyList(),
    private val registry: SiteRegistry? = null,
    /** 池里标签的 DevTools 会话入口;默认是进程里那一份。 */
    private val devtools: WebDevtools = WebDevtools.of(context),
) : WebTab.Listener, Closeable, TabBudget.Member {
    /**
     * [background]:读页用的临时后台标签,不当当前标签、不进标签列表、不算上限,读完即关(见 [openBackground])。
     * [external]:借来的标签(见 [attachExternal]),不算上限,关的时候只还回去。
     */
    private class Entry(
        val id: String, val taskId: String, var tab: WebTab?, var url: String?, var title: String, var lastUsed: Long,
        val background: Boolean = false, val external: Boolean = false,
    ) {
        /** 算进每任务和全局上限、可以被腾位置关掉的普通标签。 */
        val counted: Boolean get() = !background && !external
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val currentByTask = HashMap<String, String>()
    private val crashedByTask = HashMap<String, String>()
    /** 正交给用户处理(接管中)的标签:任务不能操作它,池也不关它、不回收它。 */
    private val locked = HashSet<String>()
    private var counter = 0
    private val main = Handler(Looper.getMainLooper())
    /** 各标签等着跑的登录状态探查(主线程动)。 */
    private val loginProbes = HashMap<String, Runnable>()

    private val _events = MutableSharedFlow<PoolEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PoolEvent> = _events.asSharedFlow()

    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = this@WebTabPool.onTrimMemory(level)
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = this@WebTabPool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    init {
        context.applicationContext.registerComponentCallbacks(memory)
        budget.join(this)
    }

    /** 在任务里开一个新标签并切过去;[url] 不为空时开始加载(不等加载完)。 */
    fun open(taskId: String, url: String?): WebTab {
        checkMain()
        makeRoom(taskId, keep = null)
        val tab = create(taskId)
        url?.let { u ->
            val refused = WebUrlPolicy.checkNavigable(u)
            if (refused == null) tab.view.loadUrl(u) else Log.w(TAG, "refused to open a non-web address: $refused")
        }
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return tab
    }

    /**
     * 开一个临时后台标签给读页用:不切过去、不挂到宿主上(没人给它排版,按 [width]×[height] 量一次,页面才有视口),
     * 不出现在 [list] 里,不算每任务和全局的标签上限(同时有几个由调用方限定),也不会被腾位置或内存回收关掉。用完调 [close]。
     */
    fun openBackground(taskId: String, width: Int, height: Int): WebTab {
        checkMain()
        val id = synchronized(this) { "t${++counter}" }
        val tab = newTab(id, taskId)
        synchronized(this) { entries[id] = Entry(id, taskId, tab, null, "", budget.tick(), background = true) }
        val w = View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        tab.view.measure(w, h)
        tab.view.layout(0, 0, width, height)
        return tab
    }

    @Synchronized
    fun current(taskId: String): WebTab? = currentByTask[taskId]?.let { entries[it]?.tab }

    /**
     * 把一个不归池管的标签(用户在浏览器页上看着的那一个)借给任务 [taskId],当成它的当前标签:之后网页工具读它、在它上面操作,
     * 和池里的标签一样。不挂到 [host] 上、不算上限、不接管它的监听;同一个标签已经借进来了就只切过去。
     * 已关的、崩了的、编号和池里的标签撞了的借不进来,返回 false。主线程。
     */
    fun attachExternal(taskId: String, tab: WebTab): Boolean {
        checkMain()
        if (tab.destroyed || tab.crashed) return false
        val entry = synchronized(this) {
            val existing = entries[tab.id]
            if (existing != null && (!existing.external || existing.tab !== tab || existing.taskId != taskId)) return false
            existing ?: Entry(tab.id, taskId, tab, tab.view.url, tab.view.title.orEmpty(), 0, external = true).also {
                entries[tab.id] = it
                crashedByTask.remove(taskId)
            }
        }
        makeCurrent(entry)
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return true
    }

    /**
     * 还回借来的标签 [tabId]:从池里拿掉,不销毁、不动它的视图;是当前标签时换成本任务最近用过的另一个(没有就没有当前标签)。
     * 不是借来的返回 false。主线程。
     */
    fun detachExternal(tabId: String): Boolean {
        checkMain()
        if (synchronized(this) { entries[tabId]?.external != true }) return false
        remove(tabId, PoolEvent.Closed(tabId, "returned"), force = true)
        return true
    }

    /**
     * 收下一个原来不归池管的标签 [tab] 当任务 [taskId] 的普通标签(借出页面的浏览器页关掉了,页面交给任务接着用):
     * 接它上面的事、算上限、可被腾位置,任务结束时随别的一起关。[current] 或任务还没有当前标签时切过去、挂到宿主上,否则在后台。
     * 已关、崩了、编号已在池里的收不下,返回 false。主线程。
     */
    fun adopt(taskId: String, tab: WebTab, current: Boolean): Boolean {
        checkMain()
        if (tab.destroyed || tab.crashed || synchronized(this) { entries.containsKey(tab.id) }) return false
        makeRoom(taskId, keep = null)
        (tab.view.parent as? android.view.ViewGroup)?.removeView(tab.view)
        tab.ownerTaskId = taskId
        tab.listener = this
        val entry = Entry(tab.id, taskId, tab, tab.view.url, tab.view.title.orEmpty(), budget.tick())
        val makeIt = synchronized(this) {
            entries[tab.id] = entry
            current || currentByTask[taskId] == null
        }
        if (makeIt) makeCurrent(entry)
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return true
    }

    /**
     * 任务结束时把要留下的标签 [tabId] 交出去(见 [PageKeeper]):从池里拿掉,不销毁、不从宿主上摘下来,也不把别的标签挂上去
     * (它是当前标签时这个任务就没有当前标签了);之后不再接它上面的事、不算上限、不随任务关。发 Closed(kept)。
     * 借来的、临时后台的、被内存回收过的、崩了的、不存在的交不出,返回 null。主线程。
     */
    fun release(tabId: String): WebTab? {
        checkMain()
        val tab = synchronized(this) {
            val e = entries[tabId]?.takeIf { it.counted } ?: return null
            val t = e.tab?.takeIf { !it.destroyed && !it.crashed } ?: return null
            entries.remove(tabId)
            locked -= tabId
            if (currentByTask[e.taskId] == tabId) currentByTask.remove(e.taskId)
            t
        }
        loginProbes.remove(tabId)?.let(main::removeCallbacks)
        tab.listener = null
        emit(PoolEvent.Closed(tabId, "kept"))
        return tab
    }

    /** [tabId] 是借来的标签(见 [attachExternal])。 */
    @Synchronized
    fun isExternal(tabId: String): Boolean = entries[tabId]?.external == true

    @Synchronized
    fun isLive(tabId: String): Boolean = entries[tabId]?.tab != null

    /** 接管开始:锁住标签,直到 [unlock]。 */
    @Synchronized
    fun lock(tabId: String) {
        locked += tabId
    }

    @Synchronized
    fun unlock(tabId: String) {
        locked -= tabId
    }

    @Synchronized
    fun isLocked(tabId: String): Boolean = tabId in locked

    /** 这个任务最近崩溃、且之后还没开过新标签的那个标签;没有为 null。 */
    @Synchronized
    fun crashedTab(taskId: String): String? = crashedByTask[taskId]

    /** 切到本任务的某个标签;不是本任务的、或不存在的为 null。被内存回收过的标签重新加载并发 [PoolEvent.Reloaded]。 */
    fun switchTo(taskId: String, tabId: String): WebTab? {
        checkMain()
        val entry = synchronized(this) { entries[tabId]?.takeIf { it.taskId == taskId } } ?: return null
        var reloaded = false
        val tab = entry.tab ?: newTab(entry.id, taskId).also { t ->
            synchronized(this) { entry.tab = t }
            entry.url?.let { t.view.loadUrl(it) }
            reloaded = true
        }
        makeCurrent(entry)
        if (reloaded) emit(PoolEvent.Reloaded(tabId))
        return tab
    }

    /** 关掉一个标签(不论哪个任务的)。任务自己关标签用 close(taskId, tabId)。 */
    fun close(tabId: String) = remove(tabId, PoolEvent.Closed(tabId, "closed"))

    /** 任务关自己的标签;不是这个任务的、不存在的、或正交给用户处理的返回 false,不关。 */
    fun close(taskId: String, tabId: String): Boolean {
        checkMain()
        if (synchronized(this) { entries[tabId]?.taskId != taskId || tabId in locked }) return false
        close(tabId)
        return true
    }

    /** 退出一个网站:过期它的 Cookie、清它的网页存储、从站点登记表里去掉(尽力而为,见 [WebLogin])。返回过期的 Cookie 个数。 */
    fun clearSiteData(host: String): Int {
        checkMain()
        return WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry).logout(host)
    }

    /** 全部退出并清除网页数据。开着的标签不关,但已经没有登录状态。 */
    fun clearAll() {
        checkMain()
        val views = synchronized(this) { entries.values.mapNotNull { it.tab?.view } }
        WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry).logoutAll(views, WebViewDatabase.getInstance(context))
    }

    /** 任务结束:关掉它开的全部标签(借来的还回去),Cookie 写盘(登录状态不随标签关闭丢失)。 */
    fun closeAll(taskId: String) {
        checkMain()
        val ids = synchronized(this) { entries.values.filter { it.taskId == taskId }.map { it.id } }
        ids.forEach { remove(it, PoolEvent.Closed(it, if (isExternal(it)) "returned" else "task_end"), force = isExternal(it)) }
        synchronized(this) { crashedByTask.remove(taskId) }
        flushCookies()
    }

    /**
     * 任务 [taskId] 结束、要留下 [keep] 那一页:先把它交出来([release]),再关掉这个任务别的标签([closeAll])。
     * 交出来的标签由调用方接着管;[keep] 为 null 或交不出时为 null(照常全关)。主线程。
     */
    fun closeKeeping(taskId: String, keep: String?): WebTab? {
        checkMain()
        val tab = keep?.takeIf { synchronized(this) { entries[it]?.taskId == taskId } }?.let(::release)
        closeAll(taskId)
        return tab
    }

    /** 本任务的标签,按打开顺序。 */
    fun list(taskId: String): List<TabInfo> {
        checkMain()
        val (rows, cur) = synchronized(this) { entries.values.filter { it.taskId == taskId && !it.background } to currentByTask[taskId] }
        return rows.map { e ->
            val live = e.tab
            TabInfo(e.id, live?.view?.url ?: e.url.orEmpty(), live?.view?.title ?: e.title, e.id == cur)
        }
    }

    /**
     * 内存紧张(RUNNING_LOW 及以上,或已在后台被要求释放)时关掉每个任务的非当前标签,只记地址;
     * App 进入后台(UI_HIDDEN)时把 Cookie 写盘。
     */
    fun onTrimMemory(level: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onTrimMemory(level) }
            return
        }
        @Suppress("DEPRECATION")
        val pressure = level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flushCookies()
        if (!pressure) return
        val victims = synchronized(this) {
            entries.values.filter { it.tab != null && it.counted && currentByTask[it.taskId] != it.id && it.id !in locked }
        }
        for (e in victims) {
            val tab = e.tab ?: continue
            e.url = tab.view.url ?: e.url
            e.title = tab.view.title ?: e.title
            synchronized(this) { e.tab = null }
            tab.destroy()
            Log.i(TAG, "tab ${e.id} released under memory pressure (level $level)")
        }
    }

    /** 关掉所有任务的标签,不再接收系统内存回调。 */
    override fun close() {
        checkMain()
        val tasks = synchronized(this) { entries.values.map { it.taskId }.toSet() }
        tasks.forEach(::closeAll)
        budget.leave(this)
        context.applicationContext.unregisterComponentCallbacks(memory)
    }

    // ---------- 全局上限 ----------

    @Synchronized
    override fun tabCount(): Int = entries.values.count { it.counted }

    @Synchronized
    override fun oldestSpare(except: String?): Pair<String, Long>? =
        entries.values.filter { it.id != except && it.counted && it.id !in locked }.minByOrNull { it.lastUsed }?.let { it.id to it.lastUsed }

    override fun evictForBudget(tabId: String) = remove(tabId, PoolEvent.Closed(tabId, "limit"))

    // ---------- 标签上的事 ----------

    override fun onPageFinished(tab: WebTab) {
        val url = tab.view.url
        synchronized(this) {
            entries[tab.id]?.let {
                it.url = url
                it.title = tab.view.title.orEmpty()
            }
        }
        url?.let { u -> WebUrlPolicy.host(u)?.takeIf { u.startsWith("http") }?.let { registry?.visited(it) } }
        flushCookies()
        scheduleLoginProbe(tab, 0)
    }

    /**
     * 页面脚本多在加载完成后才画出账号区:等页面停下来再看登录状态,登记表跟着页面的真实状态走。
     * 又加载了(跳转)就重新计时;只看任务正在用、挂在宿主上的标签;地址还在变或还在加载就再等,至多几次。主线程。
     */
    private fun scheduleLoginProbe(tab: WebTab, attempt: Int) {
        val r = registry ?: return
        loginProbes.remove(tab.id)?.let(main::removeCallbacks)
        val urlThen = tab.view.url
        val run = Runnable {
            loginProbes.remove(tab.id)
            if (tab.destroyed) return@Runnable
            val current = synchronized(this) { currentByTask[tab.ownerTaskId] == tab.id }
            if (!current || !tab.view.isAttachedToWindow) return@Runnable
            if (tab.loading || tab.view.url != urlThen) {
                if (attempt < LOGIN_PROBE_RETRIES) scheduleLoginProbe(tab, attempt + 1)
                return@Runnable
            }
            LoginProbe.probe(tab) { host, state -> r.loginObserved(host, state) }
        }
        loginProbes[tab.id] = run
        main.postDelayed(run, LOGIN_PROBE_DELAY_MS)
    }

    override fun onCrashed(tab: WebTab) {
        // 在回调之外销毁,别在 WebView 自己的回调栈里调 destroy
        main.post {
            // 临时后台标签崩了不算任务的标签崩了:读页那边自己报
            synchronized(this) { if (entries[tab.id]?.background != true) crashedByTask[tab.ownerTaskId] = tab.id }
            remove(tab.id, PoolEvent.Crashed(tab.id), force = true)
        }
    }

    /** 页面触发的下载交给它(任务把它接到工作区);为空时忽略。主线程回调:(标签, 网址, UA, Content-Disposition, 类型)。 */
    var downloadHandler: ((WebTab, String, String, String?, String?) -> Unit)? = null

    override fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {
        val h = downloadHandler
        if (h == null) Log.i(TAG, "download ignored on tab ${tab.id}") else h(tab, url, userAgent, contentDisposition, mime)
    }

    override fun onExternalLink(tab: WebTab, link: Link) {
        emit(PoolEvent.ExternalLink(tab.id, link))
    }

    override fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean {
        // 不经用户操作的弹窗一律不开
        if (!isUserGesture) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        makeRoom(tab.ownerTaskId, keep = tab.id)
        val popup = create(tab.ownerTaskId)
        popup.popupOpener = tab.view.url ?: ""
        transport.webView = popup.view
        resultMsg.sendToTarget()
        emit(PoolEvent.NewTab(popup.id, tab.ownerTaskId, fromPopup = true))
        return true
    }

    override fun onPopupRefused(tab: WebTab, url: String) {
        Log.i(TAG, "popup ${tab.id} refused: other site ${url.take(80)}")
        main.post { remove(tab.id, PoolEvent.Closed(tab.id, "popup_other_site")) }
    }

    // ---------- 内部 ----------

    private fun create(taskId: String): WebTab {
        val id = synchronized(this) { "t${++counter}" }
        val tab = newTab(id, taskId)
        val entry = Entry(id, taskId, tab, null, "", 0)
        synchronized(this) {
            entries[id] = entry
            crashedByTask.remove(taskId)
        }
        makeCurrent(entry)
        return tab
    }

    private fun newTab(id: String, taskId: String) = WebTab(context, id, taskId, assetLoaders, devtools).also { it.listener = this }

    private fun makeCurrent(entry: Entry) {
        synchronized(this) {
            currentByTask[entry.taskId] = entry.id
            entry.lastUsed = budget.tick()
        }
        // 借来的标签留在用户的界面里;宿主上原来挂着的那个照样留着(在后台照常运行)
        if (!entry.external) entry.tab?.let { host.attach(it) }
    }

    /**
     * 为本任务腾出一个位置:本任务满了关它最久没用的;所有任务合起来满了,先关本任务的,没有再关别处最久没用的。
     * [keep] 和锁住(接管中)的不关,但照样算数。实在没有可关的就不关(宁可略超,也不动用户手上的页面)。
     */
    private fun makeRoom(taskId: String, keep: String?) {
        while (true) {
            val (own, perTaskFull) = synchronized(this) {
                val all = entries.values.filter { it.taskId == taskId && it.counted }
                all.filter { it.id != keep && it.id !in locked }.minByOrNull { it.lastUsed } to (all.size >= perTask)
            }
            when {
                perTaskFull && own != null -> remove(own.id, PoolEvent.Closed(own.id, "limit"))
                budget.full() && own != null -> remove(own.id, PoolEvent.Closed(own.id, "limit"))
                budget.full() -> if (!budget.evictOldest(except = keep)) return
                else -> return
            }
        }
    }

    /** 关掉一个标签;接管中(锁住)的不关,除非 [force](渲染进程已经没了、还回借来的标签)。借来的只拿掉,不销毁。 */
    private fun remove(tabId: String, event: PoolEvent, force: Boolean = false) {
        checkMain()
        val (entry, next) = synchronized(this) {
            if (tabId in locked && !force) {
                Log.i(TAG, "tab $tabId is with the user, not closed")
                return
            }
            locked -= tabId
            val e = entries.remove(tabId) ?: return
            var nextEntry: Entry? = null
            if (currentByTask[e.taskId] == tabId) {
                nextEntry = entries.values.filter { it.taskId == e.taskId && !it.background }.maxByOrNull { it.lastUsed }
                if (nextEntry == null) currentByTask.remove(e.taskId) else currentByTask[e.taskId] = nextEntry.id
            }
            e to nextEntry
        }
        if (!entry.external) entry.tab?.let {
            host.detach(it.view)
            it.destroy()
        }
        next?.let { n ->
            // 下一个当前标签若被内存回收过,等任务切到它时再重新加载;借来的留在用户那里
            if (!n.external) n.tab?.let { host.attach(it) }
        }
        emit(event)
    }

    private fun flushCookies() {
        runCatching { WebTab.cookieManager().flush() }
    }

    private fun emit(e: PoolEvent) {
        _events.tryEmit(e)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "WebTabPool must be used on the main thread" }

    private companion object {
        const val TAG = "MikaWebTabPool"
        const val LOGIN_PROBE_DELAY_MS = 1_500L
        const val LOGIN_PROBE_RETRIES = 3
    }
}
