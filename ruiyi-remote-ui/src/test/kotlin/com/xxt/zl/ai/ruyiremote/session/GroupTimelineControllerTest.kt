package com.xxt.zl.ai.ruyiremote.session

import com.xxt.zl.ai.ruyiremote.channel.HarnessTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T16 控制器单测：用假传输验证「按群取各成员首帧快照 → 合并时间线 → 发给选中成员」
 * 的完整链路，而不依赖真机 / 中继。纯函数细节由 GroupTimelineTest 覆盖，这里只测接线。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupTimelineControllerTest {

    private val fake = FakeTransport()

    private fun messageEvent(type: String, seq: Long, time: Long, text: String) = buildJsonObject {
        put("type", type)
        put("seq", seq)
        put("time", time)
        putJsonObject("data") { putJsonObject("message") { put("text", text) } }
    }

    private fun snapshot(vararg events: JsonObject) = buildJsonObject {
        put("type", "snapshot")
        putJsonArray("records") {
            events.forEach { event -> add(buildJsonObject { put("event", event) }) }
        }
    }

    private fun memberSession(id: String, title: String) = SessionSummary(
        id = id, title = title, updatedAt = 0, running = false, cwd = null, origin = null,
    )

    @Test
    fun `open merges member snapshots into one deduped timeline`() = runTest {
        val broadcast = messageEvent("user/message", 1, 100, "请发言")
        fake.snapshots["s-astro"] = snapshot(broadcast, messageEvent("assistant/message", 2, 150, "天文"))
        fake.snapshots["s-bio"] = snapshot(broadcast, messageEvent("assistant/message", 3, 160, "生物"))

        val controller = GroupTimelineController(SessionApi(fake), this)
        controller.open(
            "讨论群",
            listOf(
                memberSession("s-astro", "天文学代表 · 讨论群"),
                memberSession("s-bio", "生物代表 · 讨论群"),
                memberSession("s-other", "普通会话"),
            ),
        )
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals("讨论群", state.group)
        assertEquals(2, state.members.size) // 普通会话被排除
        assertEquals(listOf("请发言", "天文", "生物"), state.entries.map { it.text })
        assertEquals(null, state.entries[0].speaker) // 广播标「我」
        assertEquals("天文学代表", state.entries[1].speaker)
        assertEquals("生物代表", state.entries[2].speaker)
        assertEquals("s-astro", state.entries[1].sessionId)
        assertTrue(state.error == null)
    }

    @Test
    fun `send by default broadcasts to every member and refreshes`() = runTest {
        fake.snapshots["s-astro"] = snapshot(messageEvent("assistant/message", 1, 100, "天文"))
        fake.snapshots["s-bio"] = snapshot(messageEvent("assistant/message", 2, 200, "生物"))
        val controller = GroupTimelineController(SessionApi(fake), this)
        controller.open(
            "讨论群",
            listOf(
                memberSession("s-astro", "天文学代表 · 讨论群"),
                memberSession("s-bio", "生物代表 · 讨论群"),
            ),
        )
        // 默认目标是全体（null），不指定某位成员。
        assertEquals(null, controller.state.value.targetSessionId)

        controller.send("大家发言")
        advanceUntilIdle()

        assertEquals(listOf("s-astro", "s-bio"), fake.promptTargets)
        assertEquals(listOf("大家发言", "大家发言"), fake.promptTexts)
        assertTrue(controller.state.value.sentMarker > 0L)
        assertTrue(controller.state.value.sendError == null)
    }

    @Test
    fun `send targets selected member only`() = runTest {
        fake.snapshots["s-astro"] = snapshot(messageEvent("assistant/message", 1, 100, "天文"))
        fake.snapshots["s-bio"] = snapshot(messageEvent("assistant/message", 2, 200, "生物"))
        val controller = GroupTimelineController(SessionApi(fake), this)
        controller.open(
            "讨论群",
            listOf(
                memberSession("s-astro", "天文学代表 · 讨论群"),
                memberSession("s-bio", "生物代表 · 讨论群"),
            ),
        )
        controller.setTarget("s-bio")

        controller.send("轮到你了")
        advanceUntilIdle()

        assertEquals(listOf("s-bio"), fake.promptTargets)
        assertEquals("轮到你了", fake.promptTexts.first())
        assertTrue(controller.state.value.sentMarker > 0L)
        assertTrue(controller.state.value.sendError == null)
    }

    @Test
    fun `send back to all members via null target broadcasts`() = runTest {
        fake.snapshots["s-astro"] = snapshot(messageEvent("assistant/message", 1, 100, "天文"))
        fake.snapshots["s-bio"] = snapshot(messageEvent("assistant/message", 2, 200, "生物"))
        val controller = GroupTimelineController(SessionApi(fake), this)
        controller.open(
            "讨论群",
            listOf(
                memberSession("s-astro", "天文学代表 · 讨论群"),
                memberSession("s-bio", "生物代表 · 讨论群"),
            ),
        )
        controller.setTarget("s-bio")
        controller.setTarget(null) // 切回「全体成员」

        controller.send("集体讨论")
        advanceUntilIdle()

        assertEquals(listOf("s-astro", "s-bio"), fake.promptTargets)
        assertTrue(controller.state.value.sentMarker > 0L)
    }

    @Test
    fun `send with blank text is ignored`() = runTest {
        fake.snapshots["s-astro"] = snapshot(messageEvent("assistant/message", 1, 100, "天文"))
        val controller = GroupTimelineController(SessionApi(fake), this)
        controller.open("讨论群", listOf(memberSession("s-astro", "天文学代表 · 讨论群")))

        controller.send("   ") // 空白不发

        assertTrue(fake.promptTargets.isEmpty())
        assertTrue(controller.state.value.sentMarker == 0L)
    }

    @Test
    fun `broadcast with no members is ignored`() = runTest {
        val controller = GroupTimelineController(SessionApi(fake), this)
        controller.open("讨论群", emptyList()) // 无成员可发

        controller.send("有人吗")
        advanceUntilIdle()

        assertTrue(fake.promptTargets.isEmpty())
        assertTrue(controller.state.value.sentMarker == 0L)
    }

    /** 假传输：session/follow 按 sessionId 吐预置快照；session/prompt 记录目标与文本。 */
    private class FakeTransport : HarnessTransport {
        val snapshots = HashMap<String, JsonObject>()
        val promptTargets = ArrayList<String>()
        val promptTexts = ArrayList<String>()

        override suspend fun call(endpoint: String, args: JsonObject, timeoutMs: Long?): JsonElement? {
            if (endpoint == "session/prompt") {
                val request = args["request"]?.jsonObject
                request?.get("sessionId")?.let {
                    promptTargets += it.toString().trim('"')
                }
                val content = request?.get("content")
                val text = when (content) {
                    is kotlinx.serialization.json.JsonPrimitive -> content.content
                    is JsonArray -> content.mapNotNull { part ->
                        (part as? JsonObject)?.get("text")?.toString()?.trim('"')
                    }.joinToString("")
                    else -> ""
                }
                promptTexts += text
                return JsonObject(emptyMap())
            }
            return JsonObject(emptyMap())
        }

        override fun stream(endpoint: String, args: JsonObject): Flow<JsonElement> {
            val address = args["request"]?.jsonObject?.get("address")?.jsonObject
            val sessionId = address?.get("sessionId")?.toString()?.trim('"')
            val frame = sessionId?.let { snapshots[it] }
                ?: buildJsonObject { put("type", "snapshot"); putJsonArray("records") {} }
            return flowOf(frame)
        }
    }
}
