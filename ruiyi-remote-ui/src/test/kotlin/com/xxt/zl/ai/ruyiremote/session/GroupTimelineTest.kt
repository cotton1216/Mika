package com.xxt.zl.ai.ruyiremote.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T16 单测：按群合并时间线的纯函数——发言人标注、群广播去重、时序、过滤、标题解析。 */
class GroupTimelineTest {

    private fun messageEvent(
        type: String,
        seq: Long,
        time: Long,
        text: String,
        sourceKind: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("seq", seq)
        put("time", time)
        putJsonObject("data") {
            putJsonObject("message") {
                put("text", text)
                sourceKind?.let {
                    putJsonObject("source") { put("kind", it) }
                }
            }
        }
    }

    private fun contentArrayEvent(type: String, seq: Long, time: Long, vararg texts: String): JsonObject =
        buildJsonObject {
            put("type", type)
            put("seq", seq)
            put("time", time)
            putJsonObject("data") {
                putJsonObject("message") {
                    putJsonArray("content") {
                        texts.forEach { t ->
                            add(buildJsonObject { put("type", "text"); put("text", t) })
                        }
                    }
                }
            }
        }

    private val astro = MemberMessages("s-astro", "天文学代表", emptyList())
    private val bio = MemberMessages("s-bio", "生物代表", emptyList())

    @Test
    fun `group title parsing extracts member and group by first spaced separator`() {
        val ref = parseGroupMember("s1", "天文学代表 · 科学发展史五学科讨论")
        assertEquals("天文学代表", ref?.name)
        assertEquals("科学发展史五学科讨论", ref?.group)
        assertEquals("s1", ref?.sessionId)

        // 群名自带「 · 」时按第一次切，群名保持完整——与 T14 parseGroupTitle 同规则。
        val nested = parseGroupMember("s2", "小通 · 群A · 分组")
        assertEquals("小通", nested?.name)
        assertEquals("群A · 分组", nested?.group)

        assertNull(parseGroupMember("s3", "普通会话标题"))
        assertNull(parseGroupMember("s4", null))
        assertNull(parseGroupMember("s5", "· 只有群名"))
    }

    @Test
    fun `member assistant messages are tagged with speaker and session id`() {
        val members = listOf(
            astro.copy(events = listOf(messageEvent("assistant/message", 2, 200, "我是天文学代表"))),
            bio.copy(events = listOf(messageEvent("assistant/message", 3, 300, "我是生物代表"))),
        )
        val entries = projectTimeline(members)

        assertEquals(2, entries.size)
        assertEquals("我是天文学代表", entries[0].text)
        assertEquals("天文学代表", entries[0].speaker)
        assertEquals("s-astro", entries[0].sessionId)
        assertEquals(TimelineEntry.TimelineKind.MEMBER, entries[0].kind)
        assertEquals("生物代表", entries[1].speaker)
        assertEquals("s-bio", entries[1].sessionId)
    }

    @Test
    fun `broadcast user messages shared by members are deduped to one`() {
        // 同一条群广播在两个成员会话里各留一份，应合成一条 speaker=null。
        val broadcast = messageEvent("user/message", 1, 100, "请各学科代表发言")
        val members = listOf(
            astro.copy(events = listOf(broadcast, messageEvent("assistant/message", 2, 150, "天文回答"))),
            bio.copy(events = listOf(broadcast, messageEvent("assistant/message", 3, 160, "生物回答"))),
        )
        val entries = projectTimeline(members)

        assertEquals(3, entries.size)
        assertEquals("请各学科代表发言", entries[0].text)
        assertNull(entries[0].speaker)
        assertEquals(TimelineEntry.TimelineKind.USER, entries[0].kind)
    }

    @Test
    fun `entries are ordered by time regardless of member iteration order`() {
        val members = listOf(
            astro.copy(events = listOf(
                messageEvent("assistant/message", 9, 900, "最后"),
                messageEvent("assistant/message", 1, 100, "最先"),
            )),
            bio.copy(events = listOf(messageEvent("assistant/message", 5, 500, "中间"))),
        )
        val entries = projectTimeline(members)

        assertEquals(listOf("最先", "中间", "最后"), entries.map { it.text })
        assertEquals(listOf(100L, 500L, 900L), entries.map { it.time })
    }

    @Test
    fun `synthetic and blank messages plus control events are skipped`() {
        val members = listOf(
            astro.copy(events = listOf(
                messageEvent("user/message", 1, 100, "", sourceKind = "system"),
                messageEvent("user/message", 2, 110, "系统提示", sourceKind = "system"),
                messageEvent("assistant/message", 3, 120, "   "),
                buildJsonObject { put("type", "turn/end"); put("seq", 4); put("time", 130) },
                messageEvent("assistant/message", 5, 140, "真实发言"),
            )),
        )
        val entries = projectTimeline(members)

        assertEquals(1, entries.size)
        assertEquals("真实发言", entries[0].text)
    }

    @Test
    fun `content array parts are joined into message text`() {
        val members = listOf(
            astro.copy(events = listOf(contentArrayEvent("assistant/message", 1, 100, "第一段", "第二段"))),
        )
        val entries = projectTimeline(members)

        assertEquals(1, entries.size)
        assertEquals("第一段第二段", entries[0].text)
    }

    @Test
    fun `snapshot and event frames yield events`() {
        val event = messageEvent("assistant/message", 1, 100, "hi")
        val snapshot = buildJsonObject {
            put("type", "snapshot")
            putJsonArray("records") { add(buildJsonObject { put("event", event) }) }
        }
        assertEquals(1, eventsFromSnapshot(snapshot).size)

        val eventFrame = buildJsonObject { put("type", "event"); put("event", event) }
        assertEquals(1, eventsFromFrame(eventFrame).size)
        assertEquals(1, eventsFromFrame(snapshot).size)
        assertTrue(eventsFromFrame(buildJsonObject { put("type", "hello") }).isEmpty())
    }

    // ===== T17 富文本补齐：协议图片块 / tool-result 嵌套 / SVG / 图片来源会话 =====

    private fun partsEvent(type: String, seq: Long, time: Long, parts: List<JsonObject>): JsonObject =
        buildJsonObject {
            put("type", type)
            put("seq", seq)
            put("time", time)
            putJsonObject("data") {
                putJsonObject("message") {
                    putJsonArray("content") { parts.forEach { add(it) } }
                }
            }
        }

    private fun imagePart(name: String, data: String = "AAAA") = buildJsonObject {
        put("type", "image")
        put("name", name)
        put("mediaType", "image/png")
        put("data", data)
    }

    @Test
    fun `protocol image parts are preserved and image-only message is kept`() {
        val members = listOf(
            astro.copy(events = listOf(partsEvent("assistant/message", 1, 100, listOf(imagePart("图.png"))))),
        )
        val entries = projectTimeline(members)

        assertEquals(1, entries.size)
        assertEquals("", entries[0].text)
        assertEquals(1, entries[0].images.size)
        assertEquals("图.png", entries[0].images[0].name)
        // 成员发言的图片来源就是该成员会话。
        assertEquals("s-astro", entries[0].imageSourceSessionId)
    }

    @Test
    fun `tool-result nested text and images are extracted`() {
        val nested = buildJsonObject {
            put("type", "tool-result")
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", "手抄报正文") })
                add(imagePart("hb.png", "BBBB"))
            }
        }
        val members = listOf(
            astro.copy(events = listOf(partsEvent("assistant/message", 1, 100, listOf(nested)))),
        )
        val entries = projectTimeline(members)

        assertEquals(1, entries.size)
        assertEquals("手抄报正文", entries[0].text)
        assertEquals(1, entries[0].images.size)
        assertEquals("hb.png", entries[0].images[0].name)
    }

    @Test
    fun `svg markdown text survives projection into timeline`() {
        val svg = "```svg\n<svg viewBox=\"0 0 10 10\"></svg>\n```"
        val members = listOf(
            astro.copy(events = listOf(messageEvent("assistant/message", 1, 100, svg))),
        )
        val entries = projectTimeline(members)

        assertEquals(1, entries.size)
        assertTrue(entries[0].text.contains("<svg"))
    }

    @Test
    fun `broadcast dedup distinguishes image count and records source session`() {
        // 同一条带图广播在两个成员会话各留一份；另有一条纯文本广播同时间发出。
        val withImage = partsEvent("user/message", 1, 100, listOf(imagePart("a.png")))
        val plain = messageEvent("user/message", 2, 100, "hi")
        val members = listOf(
            astro.copy(events = listOf(withImage)),
            bio.copy(events = listOf(withImage, plain)),
        )
        val entries = projectTimeline(members)

        // 带图广播按 (text,time,图片数) 去重成一条；纯文本广播键不同，保留。
        assertEquals(2, entries.size)
        val imgEntry = entries.first { it.images.size == 1 }
        assertNull(imgEntry.sessionId)
        // 广播条目也能记住实际来源会话，供 UI 读图。
        assertEquals("s-astro", imgEntry.imageSourceSessionId)
        assertTrue(entries.any { it.text == "hi" && it.images.isEmpty() })
    }
}
