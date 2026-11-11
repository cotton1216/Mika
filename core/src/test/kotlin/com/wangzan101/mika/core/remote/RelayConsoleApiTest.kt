package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** relay 作用域投影:控制台看得见什么、看不见什么、分页边界。 */
class RelayConsoleApiTest {
    private val tasks = FakeRemoteTasks()
    private val endpoints = RemoteEndpoints(tasks, "test")

    private fun start(owner: String, goal: String, conversation: String? = null): String {
        val outcome = tasks.scope(owner).start(goal, conversation, null, emptyList())
        return (outcome as StartOutcome.Started).task.id
    }

    /** [local] = true 走本机历史权限那条通道,投影不应出现。 */
    private fun get(
        path: String,
        query: String = "",
        owner: String = "ownerA",
        local: Boolean = false,
    ): Pair<Int, JsonObject> {
        val res = endpoints.handle(
            HttpRequest("GET", path, emptyMap(), byteArrayOf(), InetAddress.getLoopbackAddress(), query = query, accessOwner = owner),
            historyAccess = local,
        )
        val body = runCatching { Json.parseToJsonElement(res.body.decodeToString()).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        return res.status to body
    }

    private fun JsonObject.items(key: String) = getValue(key).jsonArray.map { it.jsonObject }

    @Test
    fun capabilitiesExposeConsoleViewsOnlyOnTheRelayChannel() {
        val relay = get("/api/v1").second.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(relay.containsAll(listOf("running", "conversations")))
        val local = get("/api/v1", local = true).second.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
        assertFalse(local.contains("running"))
        assertFalse(local.contains("conversations"))
        assertEquals(404, get("/api/v1/running", local = true).first)
        assertEquals(404, get("/api/v1/conversations", local = true).first)
    }

    @Test
    fun runningListsOnlyUnfinishedTasksAndFlagsWhoNeedsTheUser() {
        val running = start("ownerA", "打开日历")
        tasks.set(running, TaskState.Running(step = 2, note = "正在打开日历"))
        val asking = start("ownerA", "选时间")
        tasks.set(asking, TaskState.WaitingInput("上午还是下午?", listOf("上午", "下午")))
        val takeover = start("ownerA", "确认支付")
        tasks.set(takeover, TaskState.WaitingTakeover("pay", "请确认"))
        val done = start("ownerA", "已经完成")
        tasks.set(done, TaskState.Succeeded("好了"))

        val (status, body) = get("/api/v1/running")
        assertEquals(200, status)
        val rows = body.items("running")
        assertEquals(setOf(running, asking, takeover), rows.map { it.getValue("task_id").jsonPrimitive.content }.toSet())
        fun row(id: String) = rows.first { it.getValue("task_id").jsonPrimitive.content == id }
        assertTrue(row(asking).getValue("needs_you").jsonPrimitive.boolean)
        assertTrue(row(takeover).getValue("needs_you").jsonPrimitive.boolean)
        assertFalse(row(running).getValue("needs_you").jsonPrimitive.boolean)
        assertEquals("上午还是下午?", row(asking).getValue("question").jsonPrimitive.content)
        assertEquals(2, row(running).getValue("step").jsonPrimitive.int)
    }

    @Test
    fun conversationsPaginateNewestFirstWithinOneOwner() {
        repeat(5) { i ->
            val id = start("ownerA", "目标${i + 1}", conversation = "mcp-c${i + 1}")
            tasks.set(id, TaskState.Succeeded("答${i + 1}"))
        }
        repeat(2) { i -> start("ownerA", "同对话${i + 1}", conversation = "mcp-shared") }

        val first = get("/api/v1/conversations", query = "limit=2")
        assertEquals(200, first.first)
        val rows = first.second.items("conversations")
        assertEquals(2, rows.size)
        assertEquals("mcp-shared", rows[0].getValue("conversation").jsonPrimitive.content)
        assertEquals("同对话1", rows[0].getValue("title").jsonPrimitive.content)
        assertEquals(2, rows[0].getValue("turn_count").jsonPrimitive.int)
        assertEquals("mcp-c5", rows[1].getValue("conversation").jsonPrimitive.content)
        assertEquals(2, first.second.getValue("next_offset").jsonPrimitive.int)

        val last = get("/api/v1/conversations", query = "offset=4&limit=2")
        assertEquals(
            listOf("mcp-c2", "mcp-c1"),
            last.second.items("conversations").map { it.getValue("conversation").jsonPrimitive.content },
        )
        assertFalse(last.second.containsKey("next_offset"))
        val past = get("/api/v1/conversations", query = "offset=6&limit=2")
        assertEquals(0, past.second.items("conversations").size)
        assertFalse(past.second.containsKey("next_offset"))

        val other = get("/api/v1/conversations", owner = "ownerB")
        assertEquals(0, other.second.items("conversations").size)
        assertEquals(404, get("/api/v1/conversations/mcp-shared", owner = "ownerB").first)
    }

    @Test
    fun turnsUseTheSameFromWindowAsTheConsole() {
        repeat(12) { i ->
            val id = start("ownerA", "第${i + 1}轮", conversation = "mcp-long")
            tasks.set(id, TaskState.Succeeded("答${i + 1}"))
        }

        val latest = get("/api/v1/conversations/mcp-long")
        assertEquals(200, latest.first)
        assertEquals(12, latest.second.getValue("total").jsonPrimitive.int)
        assertEquals(2, latest.second.getValue("from").jsonPrimitive.int)
        val latestTurns = latest.second.items("turns")
        assertEquals(10, latestTurns.size)
        assertEquals("第3轮", latestTurns.first().getValue("user_text").jsonPrimitive.content)
        assertEquals("答12", latestTurns.last().getValue("outcome").jsonPrimitive.content)

        val older = get("/api/v1/conversations/mcp-long", query = "from=0&limit=2")
        assertEquals(0, older.second.getValue("from").jsonPrimitive.int)
        assertEquals(
            listOf("第1轮", "第2轮"),
            older.second.items("turns").map { it.getValue("user_text").jsonPrimitive.content },
        )

        val past = get("/api/v1/conversations/mcp-long", query = "from=99")
        assertEquals(12, past.second.getValue("from").jsonPrimitive.int)
        assertEquals(0, past.second.items("turns").size)

        assertEquals(404, get("/api/v1/conversations/mcp-long", owner = "ownerB").first)
        assertEquals(404, get("/api/v1/conversations/mcp-missing").first)
    }

    @Test
    fun emptySetsStayWellFormedAndPagingBoundsAreRejected() {
        val running = get("/api/v1/running")
        assertEquals(0, running.second.items("running").size)
        val conversations = get("/api/v1/conversations")
        assertEquals(0, conversations.second.items("conversations").size)
        assertFalse(conversations.second.containsKey("next_offset"))

        for (query in listOf("offset=-1", "limit=0", "limit=101", "limit=x", "unknown=1", "limit=1&limit=2"))
            assertEquals(query, 400, get("/api/v1/conversations", query = query).first)
        for (query in listOf("from=-1", "limit=0", "limit=51", "offset=0"))
            assertEquals(query, 400, get("/api/v1/conversations/mcp-none", query = query).first)
        assertEquals(400, get("/api/v1/running", query = "limit=1").first)
    }
}
