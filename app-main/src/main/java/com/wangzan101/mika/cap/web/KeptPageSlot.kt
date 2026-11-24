package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.KEEP_OPEN_HOURS
import com.wangzan101.mika.core.cap.web.KeptPageClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 任务结束后留在后台接着运行的那一页,对外的样子:来自哪个对话的哪一轮([conversationId]、[runId])、那轮的原话 [goal],
 * 页面的站点、地址、标题,留下的时刻和到时自动关的时刻(墙上时钟,毫秒)。
 */
data class KeptPage(
    val id: Long,
    val conversationId: String,
    val runId: String?,
    val goal: String,
    val host: String,
    val url: String?,
    val title: String?,
    val keptAt: Long,
    val closesAt: Long,
)

/** 留页的那个任务是谁:对话、运行记录编号、原话。 */
data class KeptOrigin(val conversationId: String, val runId: String?, val goal: String)

/**
 * 留页的纯逻辑(不碰 WebView):全局至多一页,留新的先关旧的([KeptPageClose.Replaced]);到 [closesAt][KeptPage.closesAt] 由 [expireIfDue] 关掉;
 * 关的时候放掉手里的东西([release])并报给 [onClosed](写进来源对话那一轮)。[H] 是真正撑着页面的东西(标签和它的显示)。
 * 记着最近关掉的那一页和原因,任务那一轮还没写进对话时([recordFor])也能补上。时间由调用方给,测试里用假时钟。线程安全。
 */
class KeptPageSlot<H>(
    private val clock: () -> Long,
    private val release: (H) -> Unit,
    private val onClosed: (KeptPage, KeptPageClose) -> Unit = { _, _ -> },
    private val ttlMs: Long = KEEP_OPEN_HOURS * HOUR_MS,
) {
    private val _page = MutableStateFlow<KeptPage?>(null)
    /** 此刻留着的那一页;没有为 null。 */
    val page: StateFlow<KeptPage?> = _page.asStateFlow()

    private var held: H? = null
    private var counter = 0L
    private var lastClosed: Pair<KeptPage, KeptPageClose>? = null

    /** 留下 [handle] 撑着的页面;已经留着一页时先关掉它。返回新留下的那一页。 */
    fun keep(handle: H, origin: KeptOrigin, host: String, url: String?, title: String?): KeptPage {
        close(KeptPageClose.Replaced)
        val now = clock()
        val p = synchronized(this) {
            held = handle
            KeptPage(++counter, origin.conversationId, origin.runId, origin.goal, host, url, title, now, now + ttlMs).also { _page.value = it }
        }
        return p
    }

    /** 关掉留着的那一页;没有留着的返回 false。 */
    fun close(reason: KeptPageClose): Boolean {
        val (p, h) = synchronized(this) {
            val p = _page.value ?: return false
            val h = held
            held = null
            _page.value = null
            lastClosed = p to reason
            p to h
        }
        h?.let { runCatching { release(it) } }
        onClosed(p, reason)
        return true
    }

    /** 只在 [id] 那一页还留着时关掉它(崩溃、回收的回调到得晚,别关错了后来留的那一页)。 */
    fun closeIf(id: Long, reason: KeptPageClose): Boolean = if (_page.value?.id == id) close(reason) else false

    /** 到时间了就关掉([KeptPageClose.Expired]);关了返回 true。 */
    fun expireIfDue(): Boolean {
        val p = _page.value ?: return false
        return if (clock() >= p.closesAt) closeIf(p.id, KeptPageClose.Expired) else false
    }

    /** 还剩多久自动关(毫秒,不小于 0);没有留着的为 null。 */
    fun remainingMs(): Long? = _page.value?.let { (it.closesAt - clock()).coerceAtLeast(0) }

    @Synchronized
    fun handle(): H? = held

    /**
     * 运行 [runId] 那一轮要记下的留页:(站点, 关掉的原因);还留着时原因为 null,这一轮没有留页为 null。
     * 任务收尾时那一轮才写进对话,这期间页面可能已经关了(崩了、被新留的顶掉),这里照样给得出。
     */
    @Synchronized
    fun recordFor(runId: String?): Pair<String, String?>? {
        if (runId == null) return null
        _page.value?.takeIf { it.runId == runId }?.let { return it.host to null }
        return lastClosed?.takeIf { it.first.runId == runId }?.let { (p, r) -> p.host to r.wire }
    }

    companion object {
        const val HOUR_MS = 3_600_000L
    }
}
