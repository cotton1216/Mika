package com.xxt.zl.ai.ruyiremote.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * T16（真群聊 A 路线）：把同一小通 Bot 群的多个成员会话消息，合并成单一时间线。
 *
 * 数据全部来自手机已有的 `session/list` 与 `session/follow` 快照，零协议改动、
 * 不碰 relay / host。群成员会话由 title「成员名 · 群名」识别（与 T14 同一判据）。
 *
 * 合并口径：
 * - `user/message`（群广播）在每个成员会话里都会留一份，按 (text, time) 去重成一条，
 *   speaker 为 null（界面标「我」）。
 * - `assistant/message` 是该成员的发言，speaker = 成员名，各自保留。
 * - 所有条目按 time 升序、同 time 按 kind/id 稳定排序。
 * - 丢弃系统合成消息（source.kind != user）与空白消息；控制事件（turn/end 等）不进时间线。
 */

/** 时间线上的一条消息。 */
data class TimelineEntry(
    val id: String,
    /** 发言人显示名；null 表示本机用户发出的群广播。 */
    val speaker: String?,
    /** 发言人会话 id（用户广播为 null），用于「只发给这个人」。 */
    val sessionId: String?,
    val text: String,
    val time: Long,
    val kind: TimelineKind,
    /** 协议图片块（type=="image"），普通聊天页会渲染、这里一并保留，避免丢图。 */
    val images: List<MessageImage> = emptyList(),
    /**
     * 协议图片的读取来源会话。用户广播条目的 [sessionId] 为 null，但图片实际是在某个
     * 成员会话里收到的（广播会在每个成员会话各留一份），记录首个成员会话 id 后图片
     * 才能按会话附件读取；成员发言就等于 [sessionId]。
     */
    val imageSourceSessionId: String? = null,
) {
    enum class TimelineKind { USER, MEMBER }
}

/** 一个群成员会话：真实会话 id + 从 title 解析出的成员名 + 已拉取的消息事件。 */
data class MemberMessages(
    val sessionId: String,
    val name: String,
    val events: List<JsonObject>,
)

/** 群标题解析结果（成员名, 群名），sessionId 由调用方从会话记录填入。 */
data class GroupMemberRef(val sessionId: String, val name: String, val group: String)

/**
 * 群标题解析，与 T14 `parseGroupTitle` 严格同规则（空格包裹的「 · 」，取第一次出现）。
 * 格式是 `${bot.name} · ${group.name}`：第一段是成员名，其余整段是群名。必须用第一次
 * 出现切分，群名自带「 · 」时才不会被截断；否则本处解析出的 group 与 T14 聚合出的
 * GroupBlock.group 不一致，成员会话就匹配不上群聊页。
 */
fun parseGroupMember(sessionId: String, title: String?): GroupMemberRef? {
    if (title.isNullOrBlank()) return null
    val sep = " · "
    val at = title.indexOf(sep)
    if (at <= 0) return null
    val name = title.substring(0, at).trim()
    val group = title.substring(at + sep.length).trim()
    if (name.isEmpty() || group.isEmpty()) return null
    return GroupMemberRef(sessionId, name, group)
}

/**
 * 与 SessionChat.textOf 同语义：content 可能是字符串或多段 text part。
 *
 * T17 增强：与 `messageImages` 一致，把 `tool-result` 的嵌套 content 也展开——
 * Host 有时把回复正文（含 Markdown / SVG）塞在 tool-result 里，只取顶层 text part
 * 会整段丢失，这正是群聊页看不到富文本的根因。
 */
private fun textOf(message: JsonObject): String {
    val content = message["content"] ?: return message.str("text") ?: ""
    if (content is JsonPrimitive) return content.contentOrNull ?: ""
    val parts = content as? JsonArray ?: return ""
    val expanded = parts.flatMap { element ->
        val part = element as? JsonObject ?: return@flatMap listOf(element)
        if (part.str("type") == "tool-result") (part["content"] as? JsonArray)?.toList().orEmpty()
        else listOf(element)
    }
    return expanded.mapNotNull { element ->
        val map = element as? JsonObject ?: return@mapNotNull null
        if (map.str("type") == "text") map.str("text") else null
    }.joinToString("")
}

/**
 * 纯函数：把「成员会话 → 消息事件」投影、去重、合并成时间线。
 */
fun projectTimeline(members: List<MemberMessages>): List<TimelineEntry> {
    val result = ArrayList<TimelineEntry>()
    // 广播去重键含图片数，避免同一时刻两条纯图广播都被当成同一条丢掉。
    val seenBroadcast = HashSet<Triple<String, Long, Int>>()
    for (member in members) {
        for (event in member.events) {
            val msgSeq = event.long("seq") ?: continue
            val time = event.long("time") ?: 0L
            val data = event.obj("data") ?: continue
            when (event.str("type")) {
                "user/message" -> {
                    val message = data.obj("message") ?: data
                    val text = textOf(message)
                    val images = messageImages(message)
                    if (text.isBlank() && images.isEmpty()) continue
                    // 合成消息（群系统提示等）不进时间线。
                    val synthetic = message.obj("source")?.str("kind")
                    if (synthetic != null && synthetic != "user") continue
                    // 群广播在每个成员会话里各留一份，按 (text, time) 去重。
                    if (seenBroadcast.add(Triple(text, time, images.size))) {
                        result += TimelineEntry(
                            id = "tu-$time-$msgSeq",
                            speaker = null,
                            sessionId = null,
                            text = text,
                            time = time,
                            kind = TimelineEntry.TimelineKind.USER,
                            images = images,
                            imageSourceSessionId = member.sessionId,
                        )
                    }
                }
                "assistant/message" -> {
                    val message = data.obj("message") ?: JsonObject(emptyMap())
                    val text = textOf(message)
                    val images = messageImages(message)
                    if (text.isBlank() && images.isEmpty()) continue
                    result += TimelineEntry(
                        id = "ta-${member.sessionId}-$msgSeq",
                        speaker = member.name,
                        sessionId = member.sessionId,
                        text = text,
                        time = time,
                        kind = TimelineEntry.TimelineKind.MEMBER,
                        images = images,
                        imageSourceSessionId = member.sessionId,
                    )
                }
            }
        }
    }
    return result.sortedWith(
        compareBy<TimelineEntry> { it.time }.thenBy { it.kind.ordinal }.thenBy { it.id },
    )
}

/** 从 follow 快照取事件列表（records[].event）。 */
fun eventsFromSnapshot(snapshot: JsonObject): List<JsonObject> =
    ((snapshot["records"] as? JsonArray) ?: emptyList()).mapNotNull { (it as? JsonObject)?.obj("event") }

/** 从 follow 帧提取事件：snapshot 取 records，event 帧取单条。 */
fun eventsFromFrame(frame: JsonObject): List<JsonObject> {
    return when (frame.str("type")) {
        "snapshot" -> eventsFromSnapshot(frame)
        "event" -> {
            val event = frame.obj("event") ?: return emptyList()
            listOf(event)
        }
        else -> emptyList()
    }
}
