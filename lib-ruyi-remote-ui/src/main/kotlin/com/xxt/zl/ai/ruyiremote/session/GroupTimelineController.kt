package com.xxt.zl.ai.ruyiremote.session

import com.xxt.zl.ai.ruyiremote.channel.HarnessException
import com.xxt.zl.ai.ruyiremote.channel.RemoteRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * T16（真群聊 A 路线）：群合并时间线的控制器。
 *
 * 纯手机端、零协议改动：不接 DS Bot、不改 relay / host。同一小通 Bot 群的每个成员
 * 在 `session/list` 里是一条独立会话（title 为「成员名 · 群名」），本控制器打开群聊
 * 页时对每条成员会话各取一帧 follow 快照，交给 [projectTimeline] 合并成单一时间线，
 * 并标注发言人。发送时按用户选中的成员会话调 `session/prompt`，不做实时推送——A 路线
 * 的边界是「打开即刷新」，不是真正的群房间。
 */
data class GroupChatState(
    val group: String = "",
    /** 已解析的成员（sessionId + 成员名），顺序沿用传入的会话顺序。 */
    val members: List<GroupMemberRef> = emptyList(),
    /** 按时间合并后的消息。 */
    val entries: List<TimelineEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 当前「发给谁」的目标成员会话 id；null 表示广播给全体成员（默认）。 */
    val targetSessionId: String? = null,
    val sending: Boolean = false,
    val sendError: String? = null,
    /** 发送成功后自增，UI 据此清空草稿（只清一次，失败不清）。 */
    val sentMarker: Long = 0L,
)

class GroupTimelineController(
    private val api: SessionApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(GroupChatState())
    val state: StateFlow<GroupChatState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var sendJob: Job? = null

    /**
     * 打开一个群：先用会话标题解析出成员（只留群名匹配的），同步设好元数据与目标，
     * 再异步拉取各成员最近消息。重复打开同一群会取消上一次拉取。
     */
    fun open(group: String, memberSessions: List<SessionSummary>) {
        val refs = memberSessions.mapNotNull { parseGroupMember(it.id, it.title) }
            .filter { it.group == group }
            .distinctBy { it.sessionId }
        loadJob?.cancel()
        _state.value = GroupChatState(
            group = group,
            members = refs,
            // 默认广播给全体，不强制先选一位成员。
            targetSessionId = null,
            loading = true,
        )
        loadJob = scope.launch { loadMembers(refs) }
    }

    /** 切换「发给谁」：传 null 表示广播给全体成员。 */
    fun setTarget(sessionId: String?) {
        _state.update { it.copy(targetSessionId = sessionId, sendError = null) }
    }

    /** 重新拉取全部成员的最近消息（打开页与发送成功后都走这里）。 */
    fun refresh() {
        val refs = _state.value.members
        if (refs.isEmpty()) return
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch { loadMembers(refs) }
    }

    private suspend fun loadMembers(refs: List<GroupMemberRef>) {
        val collected = ArrayList<MemberMessages>()
        var firstError: String? = null
        for (ref in refs) {
            try {
                // follow 是长流；首帧必为 snapshot（含 records[].event）。first() 取到
                // 首帧即取消收集， Host 侧流随之关闭，不会为一次刷新留下常驻订阅。
                val first = api.follow(ref.sessionId).first()
                val events = (first as? JsonObject)?.let(::eventsFromFrame) ?: emptyList()
                collected += MemberMessages(ref.sessionId, ref.name, events)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // 单个成员拉取失败不该让整群空白：记下第一处错误，继续拉其余成员。
                if (firstError == null) firstError = describeGroupError(error, ref.name)
            }
        }
        val entries = projectTimeline(collected)
        _state.update {
            it.copy(
                entries = entries,
                loading = false,
                // 只要合并出任意一条就不报错；全空才把首处错误显示出来。
                error = if (entries.isEmpty()) firstError else null,
            )
        }
    }

    /**
     * 发送消息。目标为 null 表示广播给全体成员——对每个成员会话各发一次；
     * 指定了某成员则只发那一个。单个成员失败不影响其余，全部失败才显示错误。
     * 成功后清草稿（sentMarker）并刷新时间线。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return
        val snapshot = _state.value
        // 广播目标取当前已解析的全部成员；无成员可发时直接跳过。
        val targets: List<String> = if (snapshot.targetSessionId != null) {
            listOf(snapshot.targetSessionId!!)
        } else {
            snapshot.members.map { it.sessionId }
        }
        if (targets.isEmpty()) return
        sendJob?.cancel()
        _state.update { it.copy(sending = true, sendError = null) }
        sendJob = scope.launch {
            var firstError: Throwable? = null
            var delivered = 0
            for (target in targets) {
                try {
                    api.prompt(target, trimmed)
                    delivered++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (firstError == null) firstError = error
                }
            }
            _state.update {
                it.copy(
                    sending = false,
                    // 至少送达一个就算成功；全失败才报错并保留草稿。
                    sentMarker = if (delivered > 0) System.currentTimeMillis() else it.sentMarker,
                    sendError = firstError?.let { err -> describeGroupError(err, "发送") },
                )
            }
            if (delivered > 0) refresh()
        }
    }

    /** T15 复用：群成员发言里的 markdown 图片按该成员会话的工作区相对路径读取。 */
    suspend fun loadWorkspaceImage(sessionId: String, path: String): ByteArray =
        api.loadWorkspaceImage(sessionId, path)

    /** T17：协议图片块（type=="image"）按该成员会话读取，对齐普通聊天页。 */
    suspend fun loadSessionImage(sessionId: String, image: MessageImage): ByteArray =
        api.loadImage(sessionId, image)

    fun stop() {
        loadJob?.cancel()
        sendJob?.cancel()
    }
}

private fun describeGroupError(error: Throwable, what: String): String = when (error) {
    is HarnessException -> "$what 失败：${error.message}（${error.code}）"
    is RemoteRpcException -> "$what 失败：${error.message}（${error.code}）"
    else -> "$what 失败：${error.message ?: error.javaClass.simpleName}"
}
