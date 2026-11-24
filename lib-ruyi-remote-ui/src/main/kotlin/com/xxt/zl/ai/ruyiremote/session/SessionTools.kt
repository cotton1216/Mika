package com.xxt.zl.ai.ruyiremote.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ToolPhase { Running, Succeeded, Failed, Interrupted }

data class ToolRow(
    override val key: String,
    val callId: String,
    val name: String,
    val arguments: String,
    val phase: ToolPhase,
    val output: String? = null,
    val error: String? = null,
    /**
     * 工具成功结果里以 `type="image"` 块下发的图片（桌面端生图、read_image 等）。
     * 与 [ChatRow] 里的图片一样按 attachmentId 走 Host `session/attachment` RPC 拉取。
     */
    val images: List<MessageImage> = emptyList(),
) : ChatRow {
    val argumentObject: JsonObject?
        get() = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
    val summary: String
        get() {
            val args = argumentObject ?: return arguments
            // ask_user_question 的参数里没有 description/command，回落会把整段 JSON
            // 摆到卡片上；直接显示它在问什么更有意义。
            val questions = args["questions"] as? JsonArray
            if (questions != null && questions.isNotEmpty()) {
                val first = (questions.firstOrNull() as? JsonObject)?.text("question")
                if (!first.isNullOrBlank()) {
                    return if (questions.size == 1) first else "$first（共 ${questions.size} 个问题）"
                }
            }
            return listOf("description", "command", "file_path", "path", "query", "url")
                .firstNotNullOfOrNull { args.text(it)?.takeIf(String::isNotBlank) } ?: arguments
        }
}

enum class ApprovalDecision(val wire: String) { AllowOnce("allowed-once"), Reject("rejected") }

/** 一个可选项。`description` 是模型给的取舍说明，有就展示在标签下面。 */
data class AskOption(val label: String, val description: String?)

/** 模型通过 `ask_user_question` 提出的单个问题。 */
data class AskQuestion(
    val id: String,
    val question: String,
    val header: String?,
    val options: List<AskOption>,
    val multiSelect: Boolean,
)

/** 一次工具调用可以带多个问题，回答必须整批提交。 */
data class PendingQuestions(
    val eventId: String,
    val callId: String?,
    val questions: List<AskQuestion>,
    val selected: Map<String, Set<String>> = emptyMap(),
    val drafts: Map<String, String> = emptyMap(),
    val submitting: Boolean = false,
    val error: String? = null,
) {
    /** 至少答了一题才有东西可提交，全空的批次就是「跳过」。 */
    val answered: Boolean
        get() = questions.any { question ->
            selected[question.id]?.isNotEmpty() == true || drafts[question.id]?.isNotBlank() == true
        }
}

data class ToolApproval(
    val eventId: String,
    val sessionId: String,
    val toolName: String,
    val callId: String?,
    val reason: String?,
    val submitting: Boolean = false,
    val error: String? = null,
)

/** 只投影持久工具事件；历史审批记录不能凭空变为可点击的授权请求。 */
internal fun projectTools(events: Collection<JsonObject>): Map<Long, ToolRow> {
    val rows = linkedMapOf<Long, ToolRow>()
    val calls = mutableMapOf<String, Long>()
    for (event in events) {
        val data = event["data"] as? JsonObject ?: continue
        val seq = (event["seq"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
        when (event.text("type")) {
            "tool/call" -> {
                val id = data.text("callId") ?: continue
                if (calls.containsKey(id)) continue
                calls[id] = seq
                rows[seq] = ToolRow("tool:$id", id, data.text("name") ?: "工具", data.text("arguments") ?: "", ToolPhase.Running)
            }
            "tool/result" -> {
                val message = data["message"] as? JsonObject ?: continue
                val id = message.text("toolCallId") ?: continue
                val first = calls[id] ?: seq.also { calls[id] = it }
                val old = rows[first] ?: ToolRow("tool:$id", id, "工具", "", ToolPhase.Running)
                val failure = data["error"] as? JsonObject
                val failed = failure != null || message["isError"] == JsonPrimitive(true)
                val content = message["content"] as? JsonArray
                val output = content?.mapNotNull { part ->
                    val block = part as? JsonObject
                    if (block?.text("type") == "text") block.text("text") else null
                }?.joinToString("\n").orEmpty()
                // 成功结果里的图片块（桌面生图 / read_image）与参考端一致：只认持久
                // 图片附件，不从不带 attachmentId 的结果文本里猜。失败结果就算带了
                // image 也不展示——用户想看的是它做过什么，失败时图片没有意义。
                val images = if (failed) emptyList() else messageImages(message)
                rows[first] = old.copy(
                    phase = if (failed) ToolPhase.Failed else ToolPhase.Succeeded,
                    output = output,
                    error = failure?.let { listOfNotNull(it.text("code"), it.text("reason")).joinToString("：") },
                    images = images,
                )
            }
            "turn/end" -> rows.replaceAll { _, row ->
                if (row.phase == ToolPhase.Running) row.copy(phase = ToolPhase.Interrupted) else row
            }
        }
    }
    return rows
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
