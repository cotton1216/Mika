package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.RemoteHost
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

/** 真的开一个端口(只绑本机回环),用 HTTP 客户端走一遍:门口、协议、任务工具、近期连接的统计。 */
class McpHostHttpTest {
    private val fake = FakeRemoteTasks()
    private val host = RemoteHost(fake, { TOKEN }, port = 0, allowAllInterfaces = false, serverVersion = "test")
    private val client = OkHttpClient()
    private lateinit var url: String

    @Before fun start() {
        host.start()
        url = "http://127.0.0.1:${host.boundPort}/mcp"
    }

    @After fun stop() = host.stop()

    private fun post(body: String, token: String? = TOKEN, extra: Map<String, String> = emptyMap()): Pair<Int, String> {
        val b = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
        token?.let { b.header("Authorization", "Bearer $it") }
        extra.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { return it.code to it.body!!.string() }
    }

    @Test fun fullRoundTrip() {
        val (s0, init) = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"desk","version":"1"}}}""")
        assertEquals(200, s0)
        assertEquals("2025-11-25", Json.parseToJsonElement(init).jsonObject["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").first)

        val (s1, run) = post("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"mika_run_task","arguments":{"goal":"tell me the Android version"}}}""")
        assertEquals(200, s1)
        assertTrue(run.contains("\"task_id\":\"t1\""))
        fake.set("t1", TaskState.Succeeded("Android 15"))
        val (_, wait) = post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"mika_wait_task","arguments":{"task_id":"t1","timeout_s":5}}}""")
        assertTrue(wait, wait.contains("Android 15"))

        assertEquals(1, host.activity.value.recentClients)
        assertNotNull(host.activity.value.lastRequestAtMs)
    }

    @Test fun rejectsMissingTokenAndBrowsers() {
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = null).first)
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = "nope").first)
        assertEquals(403, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", extra = mapOf("Origin" to "http://evil.example")).first)
        assertEquals(0, host.activity.value.recentClients)
    }

    @Test fun getIsNotAllowed() {
        val r = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $TOKEN").build()).execute()
        r.use { assertEquals(405, it.code) }
    }

    @Test fun keepsTheConnectionForSeveralRequests() {
        repeat(3) { assertEquals(200, post("""{"jsonrpc":"2.0","id":$it,"method":"ping"}""").first) }
        assertEquals(1, client.connectionPool.connectionCount())
    }

    @Test fun malformedHttpGetsAnErrorAndIsClosed() {
        Socket("127.0.0.1", host.boundPort).use { s ->
            s.getOutputStream().write("NONSENSE\r\n\r\n".toByteArray())
            val reply = s.getInputStream().bufferedReader().readLine()
            assertTrue(reply, reply.startsWith("HTTP/1.1 400"))
        }
        Socket("127.0.0.1", host.boundPort).use { s ->
            s.getOutputStream().write("POST /mcp HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n".toByteArray())
            assertTrue(s.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 413"))
        }
    }

    @Test fun chunkedBodyAfterExpectContinue() {
        Socket("127.0.0.1", host.boundPort).use { s ->
            val out = s.getOutputStream()
            val reader = s.getInputStream().bufferedReader()
            out.write(("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer $TOKEN\r\nContent-Type: application/json\r\n" +
                "Transfer-Encoding: chunked\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n").toByteArray())
            assertTrue(reader.readLine().startsWith("HTTP/1.1 100"))
            reader.readLine()
            val body = """{"jsonrpc":"2.0","id":9,"method":"ping"}"""
            val half = body.length / 2
            out.write("${Integer.toHexString(half)}\r\n${body.substring(0, half)}\r\n".toByteArray())
            out.write("${Integer.toHexString(body.length - half)}\r\n${body.substring(half)}\r\n0\r\n\r\n".toByteArray())
            assertTrue(reader.readLine().startsWith("HTTP/1.1 200"))
            assertTrue(reader.readText().contains("\"id\":9"))
        }
    }

    private companion object {
        const val TOKEN = "test-token-0123456789"
    }
}
