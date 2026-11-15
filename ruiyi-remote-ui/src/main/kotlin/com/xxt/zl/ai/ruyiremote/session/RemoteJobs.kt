package com.xxt.zl.ai.ruyiremote.session

import com.xxt.zl.ai.ruyiremote.channel.HarnessException
import com.xxt.zl.ai.ruyiremote.channel.HarnessTransport
import com.xxt.zl.ai.ruyiremote.channel.RemoteRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 后台任务：桌面端 `job` Remote 命名空间的只读投影 + 人工停止。
 *
 * 协议权威定义在桌面仓库：
 *   - packages/api/job-controller/src/types.ts  （job/list、job/follow、job/kill 的线上形状）
 *   - packages/jobs/jobs/lib/types/view.d.ts    （JobView / JobChunk / JobStatus）
 *
 * 这里不生成任何占位任务或输出：屏幕上的每一行都来自 Host 的 `rows` / `opened` /
 * `output` / `status` 帧。列表是整表替换（重连后的第一帧就是真相），输出按绝对
 * 字节偏移累积；一旦 Host 丢弃了更早的字节，或本地为了控制渲染量截掉了开头，
 * 都会在状态里留下明确的「丢失 / 截断」标记。
 */

/** 本地保留的输出尾部上限（UTF-16 code units），与桌面端渲染上限一致。 */
const val REMOTE_JOB_RENDER_TAIL = 128 * 1024

private val TERMINAL_JOB_STATUSES = setOf("completed", "killed", "failed")

/** `JobView` 的只读投影；字段与桌面端一一对应，未知状态保留原文而不是猜一个。 */
data class RemoteJob(
    val id: String,
    val kind: String,
    val label: String,
    val owner: String?,
    val outputLimitBytes: Long?,
    val status: String,
    val progress: String?,
    val detail: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val outputTotal: Long,
    val outputEarliest: Long,
    val spillPaths: List<String>,
) {
    /** 列表/标题用的一行名字；Host 没给 label 时才退回 id，不自己编。 */
    val title: String get() = label.trim().ifBlank { id }

    /** 已到终态：`running` / `stopping` 之外的状态都不再变化。 */
    val terminal: Boolean get() = status in TERMINAL_JOB_STATUSES

    /** 只有仍在 `running` 的任务才接受人工停止；`stopping` 已经在停了。 */
    val killable: Boolean get() = status == "running"

    val statusLabel: String
        get() = when (status) {
            "running" -> "运行中"
            "stopping" -> "正在停止"
            "completed" -> "已完成"
            "killed" -> "已取消"
            "failed" -> "失败"
            "" -> "状态未知"
            else -> status
        }
}

/** `JobChunk`：保留绝对偏移、通道与「前面丢过字节」标记。 */
data class RemoteJobChunk(
    val at: Long,
    val text: String,
    val channel: String?,
    val gapBefore: Boolean,
)

/** 当前观察中的一条任务输出。 */
data class RemoteJobOutput(
    val text: String = "",
    /** 本地渲染尾部超限，开头被截掉。 */
    val truncated: Boolean = false,
    /** Host 保留窗口或断点续读造成了缺口，更早的字节已经拿不到。 */
    val lostEarlier: Boolean = false,
    val streaming: Boolean = false,
    val error: String? = null,
    /** 下一条输出帧应从哪个绝对字节偏移继续（`opened.from` / `output.next`）。 */
    val cursor: Long? = null,
)

data class SelectedRemoteJob(
    val job: RemoteJob,
    val output: RemoteJobOutput = RemoteJobOutput(),
)

data class RemoteJobsState(
    val sessionId: String? = null,
    val jobs: List<RemoteJob> = emptyList(),
    /** 列表流还没送来第一帧。 */
    val loading: Boolean = false,
    /** 列表流仍在工作；Host 结束同步后为 false。 */
    val live: Boolean = false,
    val error: String? = null,
    /** 流正常结束但没到终态原因（例如 Host 主动关掉了列表镜像）。 */
    val closedMessage: String? = null,
    val selected: SelectedRemoteJob? = null,
    val killing: Boolean = false,
    val killError: String? = null,
    val killNotice: String? = null,
)

/**
 * 一条会话的后台任务控制器。
 *
 * 只持 [HarnessTransport]：`SessionApi` 仍是会话对话的入口，任务面板不依赖它，
 * 避免两条功能线互相牵制。`watch(sessionId)` 同时只保留一条 `job/list` 流；
 * `openJob` 只保留一条 `job/follow` 流。切换会话或切换任务都会提升代际，
 * 旧流即使还漏出一帧也会被丢弃。
 */
class RemoteJobsController(
    private val transport: HarnessTransport,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RemoteJobsState())
    val state: StateFlow<RemoteJobsState> = _state.asStateFlow()

    private var sessionGeneration = 0L
    private var followGeneration = 0L
    private var listJob: Job? = null
    private var followJob: Job? = null
    private var watchedSession: String? = null
    private var followCursor: Long? = null

    /** 跟随一条会话的任务列表。同一个会话重复调用是幂等的。 */
    fun watch(sessionId: String) {
        if (sessionId.isBlank()) return
        if (watchedSession == sessionId && listJob?.isActive == true) return
        startSession(sessionId)
    }

    /** 重新打开列表流；用于失败重试或 Host 结束同步后的手动刷新。 */
    fun retryList() {
        watchedSession?.let(::startSession)
    }

    private fun startSession(sessionId: String) {
        watchedSession = sessionId
        sessionGeneration++
        val generation = sessionGeneration
        followGeneration++
        listJob?.cancel()
        followJob?.cancel()
        listJob = null
        followJob = null
        followCursor = null
        _state.value = RemoteJobsState(sessionId = sessionId, loading = true)
        listJob = scope.launch {
            try {
                transport.stream("job/list", jobListArgs(sessionId)).collect { frame ->
                    if (generation != sessionGeneration) return@collect
                    val map = frame as? JsonObject ?: return@collect
                    if (map.str("type") != "rows") return@collect
                    val jobs = map.arr("jobs").mapNotNull { parseRemoteJob(it as? JsonObject) }
                    _state.update { it.copy(jobs = jobs, loading = false, live = true, error = null, closedMessage = null) }
                }
                if (generation == sessionGeneration) {
                    _state.update {
                        it.copy(loading = false, live = false, closedMessage = "电脑结束了任务列表同步")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == sessionGeneration) {
                    _state.update {
                        it.copy(loading = false, live = false, error = describeRemoteJobs(error, "同步后台任务"))
                    }
                }
            }
        }
    }

    /** 打开一条任务的真实输出流；列表行里的投影只作为打开瞬间的标题。 */
    fun openJob(jobId: String) {
        val sessionId = watchedSession ?: return
        if (jobId.isBlank()) return
        val row = _state.value.jobs.firstOrNull { it.id == jobId } ?: return
        if (_state.value.selected?.job?.id == jobId) return
        followGeneration++
        val generation = followGeneration
        followJob?.cancel()
        followCursor = null
        _state.update {
            it.copy(
                selected = SelectedRemoteJob(row, RemoteJobOutput(streaming = true)),
                killError = null,
                killNotice = null,
            )
        }
        launchFollow(sessionId, jobId, from = null, generation = generation)
    }

    /** 关闭当前任务的观察，回到任务列表。 */
    fun closeJob() {
        followGeneration++
        followJob?.cancel()
        followJob = null
        followCursor = null
        _state.update { it.copy(selected = null, killing = false, killError = null, killNotice = null) }
    }

    /**
     * 失败后重读输出。优先从上次 `next` 续读，避免把已经拿到的字节整段重放；
     * 没有游标时按 Host 规则从最旧的保留字节开始。
     */
    fun retryFollow() {
        val selected = _state.value.selected ?: return
        val sessionId = watchedSession ?: return
        followGeneration++
        val generation = followGeneration
        followJob?.cancel()
        val from = selected.output.cursor
        followCursor = from
        _state.update { state ->
            val current = state.selected ?: return@update state
            state.copy(
                selected = current.copy(output = current.output.copy(error = null, streaming = true)),
                killError = null,
            )
        }
        launchFollow(sessionId, selected.job.id, from = from, generation = generation)
    }

    /**
     * 人工停止一条任务。调用方（界面）负责先确认；这里只发真实 `job/kill`。
     *
     * Host 只接受会话可见且仍可停止的任务；已结束的任务应走 `already-finished`，
     * 不再出现在可停止按钮里。失败保留原文与诊断码，可直接重试。
     */
    fun kill(jobId: String) {
        val sessionId = watchedSession ?: return
        val current = _state.value
        val row = current.selected?.job?.takeIf { it.id == jobId }
            ?: current.jobs.firstOrNull { it.id == jobId }
            ?: return
        if (!row.killable || current.killing) return
        _state.update { it.copy(killing = true, killError = null, killNotice = null) }
        scope.launch {
            try {
                val value = transport.call(
                    "job/kill",
                    buildJsonObject {
                        put(
                            "request",
                            buildJsonObject {
                                put("sessionId", sessionId)
                                put("jobId", jobId)
                            },
                        )
                    },
                ) as? JsonObject
                val outcome = value?.str("outcome")
                _state.update { state ->
                    if (state.sessionId != sessionId) return@update state
                    state.copy(
                        killing = false,
                        killNotice = when (outcome) {
                            "requested" -> "已请求电脑停止这个任务。"
                            "already-finished" -> "这个任务已经结束，无需停止。"
                            null -> "电脑已受理停止请求。"
                            else -> "电脑返回了停止结果：$outcome"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { state ->
                    if (state.sessionId != sessionId) return@update state
                    state.copy(
                        killing = false,
                        killError = describeRemoteJobs(error, "停止任务"),
                    )
                }
            }
        }
    }

    /** 面板离开组合时释放当前会话的流；只影响同一个会话，避免误伤别的实例。 */
    fun release(sessionId: String) {
        if (watchedSession != sessionId) return
        stop()
    }

    fun stop() {
        sessionGeneration++
        followGeneration++
        listJob?.cancel()
        followJob?.cancel()
        listJob = null
        followJob = null
        watchedSession = null
        followCursor = null
        _state.value = RemoteJobsState()
    }

    private fun launchFollow(sessionId: String, jobId: String, from: Long?, generation: Long) {
        followCursor = from
        followJob = scope.launch {
            var sawOpened = false
            var sawStatus = false
            try {
                transport.stream("job/follow", jobFollowArgs(sessionId, jobId, from)).collect { frame ->
                    if (generation != followGeneration) return@collect
                    val map = frame as? JsonObject ?: return@collect
                    when (map.str("type")) {
                        "opened" -> {
                            val job = parseRemoteJob(map.obj("job")) ?: return@collect
                            val start = map.num("from") ?: job.outputEarliest
                            sawOpened = true
                            followCursor = start
                            _state.update { state ->
                                val selected = state.selected ?: return@update state
                                if (selected.job.id != jobId) return@update state
                                // `from` 大于 Host 的最旧保留字节（或本地还没有任何文本却从 >0
                                // 开始）意味着前面有一段拿不到；必须显式告诉用户。
                                val lost = start > job.outputEarliest ||
                                    (selected.output.text.isEmpty() && start > 0)
                                state.copy(
                                    jobs = state.jobs.map { if (it.id == job.id) job else it },
                                    selected = selected.copy(
                                        job = job,
                                        output = selected.output.copy(
                                            streaming = true,
                                            error = null,
                                            lostEarlier = selected.output.lostEarlier || lost,
                                            cursor = start,
                                        ),
                                    ),
                                )
                            }
                        }

                        "output" -> {
                            val chunks = map.arr("chunks").mapNotNull { parseRemoteJobChunk(it as? JsonObject) }
                            val lossy = map.bool("lossy") == true
                            val next = map.num("next")
                            if (next != null) followCursor = next
                            val gap = lossy || chunks.any { it.gapBefore }
                            val appended = chunks.joinToString("") { it.text }
                            _state.update { state ->
                                val selected = state.selected ?: return@update state
                                if (selected.job.id != jobId) return@update state
                                state.copy(
                                    selected = selected.copy(
                                        output = selected.output.appendTail(appended, gap).copy(cursor = next ?: selected.output.cursor),
                                    ),
                                )
                            }
                        }

                        "status" -> {
                            val job = parseRemoteJob(map.obj("job")) ?: return@collect
                            sawStatus = true
                            _state.update { state ->
                                val selected = state.selected ?: return@update state
                                if (selected.job.id != job.id) return@update state
                                state.copy(
                                    jobs = state.jobs.map { if (it.id == job.id) job else it },
                                    selected = selected.copy(
                                        job = job,
                                        output = selected.output.copy(
                                            streaming = false,
                                            error = null,
                                            cursor = followCursor ?: selected.output.cursor,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
                if (generation == followGeneration) {
                    _state.update { state ->
                        val selected = state.selected ?: return@update state
                        val message = when {
                            !sawOpened -> "任务输出流在返回任务信息前结束了"
                            !sawStatus -> "任务输出流在任务结束前断开了，后续输出可能缺失"
                            else -> null
                        }
                        state.copy(
                            selected = selected.copy(
                                output = selected.output.copy(streaming = false, error = message),
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == followGeneration) {
                    _state.update { state ->
                        val selected = state.selected ?: return@update state
                        state.copy(
                            selected = selected.copy(
                                output = selected.output.copy(
                                    streaming = false,
                                    error = describeRemoteJobs(error, "读取任务输出"),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 解析 */

internal fun parseRemoteJob(value: JsonObject?): RemoteJob? {
    value ?: return null
    val id = value.str("id")?.takeIf { it.isNotBlank() } ?: return null
    val output = value.obj("output")
    return RemoteJob(
        id = id,
        kind = value.str("kind").orEmpty(),
        label = value.str("label").orEmpty(),
        owner = value.str("owner")?.takeIf { it.isNotBlank() },
        outputLimitBytes = value.num("outputLimitBytes"),
        status = value.str("status").orEmpty(),
        progress = value.str("progress")?.takeIf { it.isNotBlank() },
        detail = value.str("detail")?.takeIf { it.isNotBlank() },
        startedAt = value.num("startedAt") ?: 0,
        finishedAt = value.num("finishedAt"),
        outputTotal = output?.num("total") ?: 0,
        outputEarliest = output?.num("earliest") ?: 0,
        spillPaths = output?.arr("spillPaths").orEmpty().mapNotNull { raw ->
            (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        },
    )
}

internal fun parseRemoteJobChunk(value: JsonObject?): RemoteJobChunk? {
    value ?: return null
    val text = value.str("text") ?: return null
    return RemoteJobChunk(
        at = value.num("at") ?: 0,
        text = text,
        channel = value.str("channel"),
        gapBefore = value.bool("gapBefore") == true,
    )
}

/**
 * 追加一段输出，并把本地保留上限之外的开头截掉。
 *
 * 截断点若落在 UTF-16 代理对中间，向后挪一个位置，避免把 emoji 劈成半个字符；
 * 截断和「Host 缺口」都归入 [RemoteJobOutput.lostEarlier]，界面据此明确提示。
 */
internal fun RemoteJobOutput.appendTail(text: String, gap: Boolean): RemoteJobOutput {
    if (text.isEmpty() && !gap) return this
    var merged = this.text + text
    var truncatedNow = false
    if (merged.length > REMOTE_JOB_RENDER_TAIL) {
        var cut = merged.length - REMOTE_JOB_RENDER_TAIL
        if (cut < merged.length && merged[cut].isLowSurrogate()) cut++
        merged = merged.substring(cut)
        truncatedNow = true
    }
    return copy(
        text = merged,
        truncated = this.truncated || truncatedNow,
        lostEarlier = this.lostEarlier || gap || truncatedNow,
    )
}

private fun jobListArgs(sessionId: String): JsonObject = buildJsonObject {
    put(
        "request",
        buildJsonObject { put("sessionId", sessionId) },
    )
}

private fun jobFollowArgs(sessionId: String, jobId: String, from: Long?): JsonObject = buildJsonObject {
    put(
        "request",
        buildJsonObject {
            put("sessionId", sessionId)
            put("jobId", jobId)
            if (from != null) put("from", from)
        },
    )
}

private fun describeRemoteJobs(error: Throwable, what: String): String = when (error) {
    is HarnessException -> "$what 失败：${error.message}（${error.code}）"
    is RemoteRpcException ->
        if (error.isTransportClosed) "与电脑的连接已断开，请重新连接后再试。"
        else "$what 失败：${error.message}（${error.code}）"
    else -> "$what 失败：${error.message ?: error.javaClass.simpleName}"
}
