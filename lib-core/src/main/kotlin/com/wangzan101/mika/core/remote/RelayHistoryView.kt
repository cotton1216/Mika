package com.wangzan101.mika.core.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 只读投影:把 relay 归属的任务集折叠成 iOS 控制台要的 running / conversations / turns 三个视图。
 *
 * [tasks] 必须是已经 `scope(owner)` 过的出入口,因此这里看不到本机个人历史,也不复用 [RemoteHistory]
 * (后者是无条件全量返回,权限语义不同)。投影只读,不提供回答、插话或取消。
 */
class RelayHistoryView(private val tasks: RemoteTasks) {
    /** 未终结的任务,新的在前。`needs_you` 表示此刻需要用户介入(回答或到手机上处理)。 */
    fun running(): JsonObject {
        val open = scan()
            .filter { !RemoteStatus.finished(RemoteStatus.of(it.state)) }
            .sortedByDescending { it.createdAtMs }
            .take(MAX_RUNNING)
        return buildJsonObject {
            putJsonArray("running") {
                open.forEach { task ->
                    val view = RemoteStatus.describe(task)
                    add(buildJsonObject {
                        view.forEach { (key, value) -> put(key, value) }
                        put("needs_you", needsYou(RemoteStatus.of(task.state)))
                    })
                }
            }
        }
    }

    /** 按对话聚合 relay 任务,最近有动作的在前。 */
    fun conversations(offset: Int, limit: Int): JsonObject {
        val groups = scan().groupBy { it.conversation }
            .map { (id, rows) -> id to rows.sortedBy { it.createdAtMs } }
            .sortedByDescending { (_, rows) -> rows.last().createdAtMs }
        val window = groups.drop(offset).take(limit)
        val next = offset + window.size
        return buildJsonObject {
            putJsonArray("conversations") {
                window.forEach { (id, rows) ->
                    add(buildJsonObject {
                        put("conversation", id)
                        put("title", title(rows.first().goal))
                        put("updated_at_ms", rows.last().createdAtMs)
                        put("turn_count", rows.size)
                    })
                }
            }
            if (next < groups.size) put("next_offset", next)
        }
    }

    /**
     * 一次对话里的轮次,按时间从早到晚。
     *
     * `from` 是窗口起点下标(从最早一轮 0 起算),与响应里的 `from` 同义;缺省时取最新一页。
     * 控制台"加载更早"用 `from - limit` 请求,两边语义一致,不会重叠也不会漏。
     * 返回 null 表示这个对话不属于当前归属。
     */
    fun turns(conversation: String, from: Int?, limit: Int): JsonObject? {
        val rows = scan().filter { it.conversation == conversation }.sortedBy { it.createdAtMs }
        if (rows.isEmpty()) return null
        val total = rows.size
        val start = (from ?: maxOf(0, total - limit)).coerceIn(0, total)
        val end = minOf(total, start + limit)
        return buildJsonObject {
            put("conversation", conversation)
            put("title", title(rows.first().goal))
            put("total", total)
            put("from", start)
            putJsonArray("turns") { rows.subList(start, end).forEach { add(turn(it)) } }
        }
    }

    private fun turn(task: RemoteTask): JsonObject {
        val view = RemoteStatus.describe(task)
        return buildJsonObject {
            put("user_text", task.goal)
            put("outcome", view.string("answer") ?: view.string("reason") ?: "")
            put("status", RemoteStatus.of(task.state))
            (view.string("note") ?: view.string("progress"))?.let { put("note", it) }
            put("truncated", false)
        }
    }

    /** 任务页是游标式的,这里按上限翻完;出现重复或空游标立即停止,避免死循环。 */
    private fun scan(): List<RemoteTask> {
        val all = ArrayList<RemoteTask>()
        val seen = HashSet<String>()
        var before: String? = null
        while (all.size < MAX_SCAN) {
            val page = tasks.page(SCAN_PAGE, before)
            all += page.tasks
            val next = page.nextBefore ?: break
            if (next == before || !seen.add(next)) break
            before = next
        }
        return all
    }

    private fun title(goal: String): String =
        goal.lineSequence().firstOrNull()?.trim().orEmpty().take(TITLE_MAX)

    private fun needsYou(status: String) =
        status == RemoteStatus.WAITING_FOR_ANSWER || status == RemoteStatus.WAITING_ON_PHONE

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private companion object {
        const val SCAN_PAGE = 200
        const val MAX_SCAN = 1_000
        const val MAX_RUNNING = 50
        const val TITLE_MAX = 80
    }
}
