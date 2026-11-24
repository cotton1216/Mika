package com.wangzan101.mika.cap.web

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.cap.web.KeptPageClose
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真机测试:任务结束后留下一页接着运行。一套不挂在任务上的网页([WebStack],和任务里一样建),打开本地的计时页,
 * 用 web_keep_open 那条路标下它,再「结束任务」([WebStack.close]):那一页不随任务关,交到进程里那一份 [PageKeeper],
 * 页面里的计时器和画面帧接着涨;没标的一页照常关;留新的一页顶掉旧的;最后经 [PageKeeper] 关掉。
 * 秒数可用 `-e keptRunSec N` 调(默认 5)。
 */
@RunWith(AndroidJUnit4::class)
class KeptPageDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val keeper = WebOwner.of(ctx).pageKeeper
    private val stacks = mutableListOf<WebStack>()
    private val seconds = InstrumentationRegistry.getArguments().getString("keptRunSec")?.toLongOrNull()?.coerceIn(1, 120) ?: 5L

    @Before
    fun setUp() {
        instr.runOnMainSync { keeper.close(KeptPageClose.User) }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            stacks.forEach { runCatching { it.close() } }
            keeper.close(KeptPageClose.User)
        }
    }

    private fun stack(task: String): WebStack {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        lateinit var s: WebStack
        instr.runOnMainSync {
            val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
            s = WebStack.create(ctx, task, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi, listOf(loader))
            s.keepOrigin = { KeptOrigin("conv-$task", "run-$task", "打开计时页,挂着别关") }
        }
        stacks += s
        return s
    }

    private fun open(s: WebStack) = runBlocking { s.screen.navigate(TICKER, newTab = false) }.also { assertTrue("ticker loaded", it.loaded) }

    /** 页面里此刻的计数(计时器, 画面帧)。 */
    private fun counts(tab: WebTab): Pair<Long, Long> {
        val latch = CountDownLatch(1)
        var raw = ""
        instr.runOnMainSync { tab.view.evaluateJavascript("window.ticks + ',' + window.frames") { raw = it; latch.countDown() } }
        assertTrue("page answered", latch.await(5, TimeUnit.SECONDS))
        val (a, b) = raw.trim('"').split(',')
        return a.toLong() to b.toLong()
    }

    /** 任务结束(和 TaskRun 收尾一样在主线程上关)。 */
    private fun endTask(s: WebStack) {
        instr.runOnMainSync { s.close() }
        stacks -= s
    }

    @Test
    fun keptPageKeepsRunningAfterTheTaskEnds() {
        val s = stack("keep-1")
        open(s)
        val tab = s.pool.current("keep-1")!!
        val r = runBlocking { s.screen.keepOpen(true, "测试:接着运行") }!!
        assertTrue(r.keep)
        assertEquals("appassets.androidplatform.net", r.host)
        assertNull("nothing kept before", r.replaces)
        endTask(s)

        // 任务的池关了,这一页没关:交到了 PageKeeper,还挂在它那块显示上
        assertFalse("kept tab not destroyed at task end", tab.destroyed)
        assertTrue(tab === keeper.keptTab())
        val page = keeper.page.value!!
        assertEquals("conv-keep-1", page.conversationId)
        assertEquals("run-keep-1", page.runId)
        assertEquals("appassets.androidplatform.net" to null, keeper.recordFor("run-keep-1"))
        assertNotNull("still on its display", tab.view.parent)

        // 页面接着跑:计时器、画面帧都在涨
        val before = counts(tab)
        Thread.sleep(seconds * 1_000)
        val after = counts(tab)
        assertTrue("timer advanced ${before.first} -> ${after.first}", after.first - before.first >= seconds * 5)
        assertTrue("frames advanced ${before.second} -> ${after.second}", after.second > before.second)
        // 画中画同一来源的画面也有
        assertNotNull("live frame", runBlocking { keeper.frames().let { f -> kotlinx.coroutines.withTimeoutOrNull(5_000) { f.first { it != null } } } })

        // 经 PageKeeper 关掉:标签销毁,记下原因
        instr.runOnMainSync { assertTrue(keeper.close(KeptPageClose.User)) }
        assertTrue(tab.destroyed)
        assertNull(keeper.page.value)
        assertEquals("appassets.androidplatform.net" to "user", keeper.recordFor("run-keep-1"))
    }

    @Test
    fun unmarkedPagesCloseWithTheTask() {
        val s = stack("plain")
        open(s)
        val tab = s.pool.current("plain")!!
        endTask(s)
        assertTrue(tab.destroyed)
        assertNull(keeper.page.value)
    }

    @Test
    fun takenBackMarkClosesWithTheTask() {
        val s = stack("back")
        open(s)
        val tab = s.pool.current("back")!!
        runBlocking { s.screen.keepOpen(true, "x") }
        assertFalse(runBlocking { s.screen.keepOpen(false, "不用了") }!!.keep)
        endTask(s)
        assertTrue(tab.destroyed)
        assertNull(keeper.page.value)
    }

    @Test
    fun keepingAnotherPageClosesTheOldOne() {
        val a = stack("first")
        open(a)
        val first = a.pool.current("first")!!
        runBlocking { a.screen.keepOpen(true, "x") }
        endTask(a)
        val b = stack("second")
        open(b)
        // 已经留着一页:结果里说清会顶掉它
        assertEquals("appassets.androidplatform.net", runBlocking { b.screen.keepOpen(true, "y") }!!.replaces)
        endTask(b)
        assertTrue("old kept page closed", first.destroyed)
        assertEquals("conv-second", keeper.page.value?.conversationId)
        assertEquals("appassets.androidplatform.net" to "replaced", keeper.recordFor("run-first"))
    }

    private companion object {
        const val TICKER = "https://appassets.androidplatform.net/assets/web-fixtures/ticker.html"
    }
}
