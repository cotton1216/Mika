package com.wangzan101.mika.about

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private var response: MockResponse = MockResponse().setResponseCode(500)
    private val hits = mutableListOf<String>()
    private var clock = 1_000_000_000L

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits += request.path.orEmpty()
                return response
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun checker(current: String = "0.1.0", currentCode: Int = 10) = UpdateChecker(
        tmp.root.resolve("update-v2.json"), current, currentCode,
        feedUrl = server.url("/ruyi/android/latest.json").toString(),
        client = OkHttpClient(),
        now = { clock },
    )

    private fun feed(version: String, code: Int? = 20, digest: String = "a".repeat(64)): MockResponse {
        val codeField = code?.let { ",\"versionCode\":$it" } ?: ""
        val body = """{"version":"$version"$codeField,"changelog":"- 新东西","publishedAt":"2026-11-13T08:30:00Z","policy":"optional","download":{"url":"https://oss-chuangxin.thchengtay.com/ai/ruyi/android/ruyi-$version-universal.apk","sha256":"$digest","size":12345}}"""
        return MockResponse().setBody(body)
    }

    @Test
    fun newerVersionCodeShowsUpdate() = runTest {
        response = feed("0.2.0")
        val c = checker()
        c.check(manual = true)
        val s = c.state.value
        assertEquals(CheckStatus.Done, s.status)
        assertEquals("0.2.0", s.update?.version)
        assertTrue(s.hasUpdate)
        assertEquals(1, s.manualRounds)
        assertEquals(listOf("/ruyi/android/latest.json"), hits)
        val req = server.takeRequest()
        assertEquals("Yuan/0.1.0", req.getHeader("User-Agent"))
        assertNull("静态 feed 不带 GitHub 头", req.getHeader("Accept"))
        assertNull(req.getHeader("X-GitHub-Api-Version"))
    }

    /** 按 versionCode 判新旧:版本号写得更花但 code 没涨,不算更新。 */
    @Test
    fun sameVersionCodeIsUpToDate() = runTest {
        response = feed("0.9.0", code = 10)
        val c = checker()
        c.check(manual = true)
        assertEquals(CheckStatus.Done, c.state.value.status)
        assertNull(c.state.value.update)
        assertEquals("0.9.0", c.state.value.latest?.version)
    }

    @Test
    fun newerVersionNameWithSameCodeDoesNotUpdate() = runTest {
        response = feed("0.1.1", code = 10)
        checker().autoCheck()
        assertNull(checker().state.value.update)
    }

    /** feed 没给 code(老数据/异常)时退回按版本号字符串比。 */
    @Test
    fun versionCodeMissingFallsBackToVersionName() = runTest {
        response = feed("0.2.0", code = null)
        val c = checker()
        c.check(manual = true)
        assertEquals("0.2.0", c.state.value.update?.version)

        val same = checker(current = "0.2.0", currentCode = 10)
        same.check(manual = true)
        assertNull(same.state.value.update)
        assertNotNull(same.state.value.latest)
    }

    /** feed 为空(还没发布版本)是查成功,按「已是最新」处理,并把上一次的红点清掉。 */
    @Test
    fun emptyFeedIsNotAFailure() = runTest {
        response = feed("0.2.0")
        val c = checker()
        c.check(manual = true)
        assertEquals("0.2.0", c.state.value.update?.version)

        response = MockResponse().setBody("{}")
        c.check(manual = true)
        assertEquals(CheckStatus.Done, c.state.value.status)
        assertNull(c.state.value.latest)
        assertNull(c.state.value.update)
        assertEquals(2, c.state.value.manualRounds)
    }

    /** 网关回了一页 HTML 之类读不懂的响应仍算这次失败,也不清掉上一次查到的新版。 */
    @Test
    fun unreadableBodyIsAFailure() = runTest {
        response = feed("0.2.0")
        val c = checker()
        c.check(manual = true)

        response = MockResponse().setBody("<html>gateway</html>")
        c.check(manual = true)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertEquals("0.2.0", c.state.value.update?.version)
    }

    @Test
    fun serverErrorIsAFailure() = runTest {
        response = MockResponse().setResponseCode(500)
        val c = checker()
        c.check(manual = true)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertNull(c.state.value.update)
    }

    /** 自动检查失败不改界面上的状态;每次打开都查,只挡住两分钟内连着来的。 */
    @Test
    fun autoCheckIsSilentAndRunsOnEveryOpenButNotInBursts() = runTest {
        val c = checker()
        assertTrue(c.autoCheck())
        assertEquals(CheckStatus.Idle, c.state.value.status)
        assertEquals(0, c.state.value.manualRounds)
        val before = hits.size
        clock += UpdateSchedule.MIN_GAP_MS - 1
        assertFalse(c.autoCheck())
        assertEquals(before, hits.size)
        clock += 1
        response = feed("0.2.0")
        assertTrue(c.autoCheck())
        assertEquals("0.2.0", c.state.value.update?.version)
        assertEquals(CheckStatus.Idle, c.state.value.status)
    }

    /** 查到的版本存盘:重开 App 不联网也有红点;装上新版后红点消失;上次自动检查的时间也记着。 */
    @Test
    fun foundUpdateSurvivesRestart() = runTest {
        response = feed("0.2.0", code = 20)
        checker(currentCode = 10).autoCheck()
        response = MockResponse().setResponseCode(500)

        val again = checker(currentCode = 10)
        again.load()
        assertEquals("0.2.0", again.state.value.update?.version)
        assertFalse("just checked", again.autoCheck())

        val upgraded = checker(currentCode = 20)
        upgraded.load()
        assertNull(upgraded.state.value.update)
        assertNotNull(upgraded.state.value.latest)
    }

    /** 同时进来的两次自动检查(查还没完)只真的查一次;隔十分钟再打开照样查。 */
    @Test
    fun concurrentAutoChecksHitServerOnce() = runTest {
        response = feed("0.2.0")
        val c = checker()
        val ran = listOf(async { c.autoCheck() }, async { c.autoCheck() }).awaitAll()
        assertEquals(1, ran.count { it })
        assertEquals(1, hits.size)
        clock += 10 * 60 * 1000L
        assertTrue(c.autoCheck())
        assertEquals(2, hits.size)
    }

    @Test
    fun scheduleRules() {
        assertTrue(UpdateSchedule.due(null, 5))
        assertFalse(UpdateSchedule.due(1_000, 1_000 + UpdateSchedule.MIN_GAP_MS - 1))
        assertTrue(UpdateSchedule.due(1_000, 1_000 + UpdateSchedule.MIN_GAP_MS))
        assertTrue("clock moved back", UpdateSchedule.due(10_000, 5_000))
    }
}
