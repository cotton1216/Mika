package com.xxt.zl.ai.ruyiremote.session

import com.xxt.zl.ai.ruyiremote.channel.HarnessException
import com.xxt.zl.ai.ruyiremote.channel.HarnessTransport
import com.xxt.zl.ai.ruyiremote.channel.RemoteRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** `session/list` 的一条记录。 */
data class SessionSummary(
    val id: String,
    val title: String?,
    val updatedAt: Long,
    val running: Boolean,
    val cwd: String?,
    val origin: String?,
    /** 还没产生过任何一轮。抽屉里据此区分「空会话」和「有内容的会话」。 */
    val blank: Boolean = false,
    /** Host 侧是否有一个活着的 Agent；关掉的会话点进去只能看历史。 */
    val agentAvailable: Boolean = false,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            // Host 大多数时候跑在 Windows 上，cwd 是 `C:\Users\…\profiles\desktop`。
            // 只按 `/` 切会把整条路径当成「目录名」显示在列表里（真机踩到过）。
            ?: cwd?.trimEnd('/', '\\')
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.takeIf { it.isNotBlank() }
            ?: "会话 ${id.take(8)}"
}

data class HistoryPage(val events: List<JsonObject>, val hasMore: Boolean)

/**
 * 会话协议的最小真实实现：列表、follow、历史页、prompt、cancel。
 * 不生成任何占位消息；界面看到的每一行都来自 Host 的响应或事件。
 */
class SessionApi(@Volatile private var transport: HarnessTransport) {

    /**
     * 连接层重连成功后换底层隧道。
     *
     * 会话控制器不随连接对象重建（重建会把已加载的聊天记录整页清空），而是沿用
     * 同一个 [SessionApi]，只替换它背后的 [HarnessTransport]。下一次 follow 续传
     * 或 RPC 就会走新隧道。字段用 `@Volatile`：`call`/`stream` 在 IO/Default 协程
     * 里读，本方法在 Main 线程写，要保证跨线程可见。
     */
    fun switchTransport(next: HarnessTransport) {
        transport = next
    }


    /**
     * `session/create`：只由 Host 决定会话 id 与目录。
     *
     * 不传 `cwd` 时由 Host 用默认目录（与桌面端「新建会话」一致）；传 `sessionId`
     * 只是请求 Host 复用它没有写过的空白会话，最终身份仍以返回值为主。
     */
    suspend fun create(cwd: String? = null, sessionId: String? = null): String {
        val value = transport.call(
            "session/create",
            buildJsonObject {
                put(
                    "request",
                    buildJsonObject {
                        if (cwd != null) put("cwd", cwd)
                        if (sessionId != null) put("sessionId", sessionId)
                    },
                )
            },
        ) as? JsonObject ?: throw HarnessException("INVALID_MESSAGE", "session/create 返回了非对象结果")
        val id = value.str("sessionId")?.takeIf { it.isNotBlank() }
            ?: throw HarnessException("INVALID_MESSAGE", "session/create 没有返回 sessionId")
        return id
    }

    suspend fun listSessions(): List<SessionSummary> {
        val value = transport.call("session/list", buildJsonObject { put("_request", buildJsonObject {}) }) as? JsonObject
            ?: return emptyList()
        return ((value["items"] as? JsonArray) ?: emptyList()).mapNotNull { item ->
            val map = item as? JsonObject ?: return@mapNotNull null
            val id = map.str("sessionId") ?: return@mapNotNull null
            val projections = map.obj("projections")
            SessionSummary(
                id = id,
                title = projections?.obj("values")?.str("title") ?: map.str("title"),
                updatedAt = map.long("updatedAt") ?: 0,
                running = map.bool("running") == true,
                cwd = map.str("cwd"),
                origin = map.str("origin"),
                blank = map.bool("blank") == true ||
                    projections?.obj("values")?.obj("sessionListMetadata")?.bool("blank") == true,
                agentAvailable = map.bool("agentAvailable") == true,
            )
        }.filterNot { it.origin == "subagent" }
    }

    suspend fun loadModelCatalog(): ModelCatalog {
        val value = transport.call("session/modelCatalog", JsonObject(emptyMap())) as? JsonObject
            ?: throw HarnessException("INVALID_MESSAGE", "session/modelCatalog 返回了非对象结果")
        return parseModelCatalog(value)
    }

    suspend fun selectModel(sessionId: String, selection: ModelSelection): ModelSelection {
        val value = transport.call(
            "session/selectModel",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("sessionId", sessionId)
                    put("provider", selection.provider)
                    put("model", selection.model)
                    selection.reasoningEffort?.let { put("reasoningEffort", it) }
                })
            },
        ) as? JsonObject ?: throw HarnessException("INVALID_MESSAGE", "session/selectModel 返回了非对象结果")
        // Host 没回 selected 就说明选择没有被记录；不能拿请求值冒充当成功。
        return parseSelection(value.obj("selected"))
            ?: throw HarnessException("INVALID_MESSAGE", "session/selectModel 没有返回 selected")
    }

    fun followModelSelection(): Flow<JsonElement> = transport.stream("session/control", JsonObject(emptyMap()))

    suspend fun loadJobs(sessionId: String): Flow<JsonElement> = transport.stream(
        "job/list", buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) },
    )

    fun follow(sessionId: String): Flow<JsonElement> = transport.stream(
        "session/follow",
        buildJsonObject {
            put(
                "request",
                buildJsonObject {
                    put("address", sessionAddress(sessionId))
                    put("assistantStream", true)
                    put("maxMessages", 500)
                    put(
                        "turnWindow",
                        buildJsonObject {
                            put("minMessages", 50)
                            put("minTurns", 2)
                        },
                    )
                },
            )
        },
    )

    suspend fun prompt(sessionId: String, text: String, requestId: String = UUID.randomUUID().toString(), images: List<PromptImage> = emptyList()): String {
        transport.call(
            "session/prompt",
            buildJsonObject {
                put(
                    "request",
                    buildJsonObject {
                        put("requestId", requestId)
                        put("sessionId", sessionId)
                        put("mode", "queue")
                        put(
                            "content",
                            promptContent(text, images),
                        )
                    },
                )
            },
        )
        return requestId
    }

    suspend fun loadImage(sessionId: String, image: MessageImage): ByteArray = loadSessionImage(transport, sessionId, image)

    /** T15：按工作区相对路径读 markdown 图片（![](file.png) 这类无附件引用的图）。 */
    suspend fun loadWorkspaceImage(sessionId: String, path: String): ByteArray =
        loadWorkspaceImage(transport, sessionId, path)

    fun hostEvents(): Flow<JsonElement> = transport.stream("\$events", JsonObject(emptyMap()))

    suspend fun respondApproval(clientId: String, eventId: String, decision: ApprovalDecision) {
        respondEvent(clientId, eventId, JsonPrimitive(decision.wire))
    }

    /**
     * 所有被转发到 Client 的 waterfall 都靠这条通道回结果：审批回一个字符串，
     * 用户提问回一整批 answers。
     */
    suspend fun respondEvent(clientId: String, eventId: String, value: JsonElement) {
        transport.call("\$events/result", buildJsonObject {
            put("clientId", clientId)
            put("eventId", eventId)
            put("outcome", buildJsonObject {
                put("kind", "result")
                put("value", value)
            })
        })
    }

    suspend fun cancel(sessionId: String) {
        transport.call("session/cancel", buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) })
    }

    suspend fun history(sessionId: String, throughSeq: Long, beforeSeq: Long): HistoryPage {
        val value = transport.call(
            "session/page",
            buildJsonObject {
                put(
                    "request",
                    buildJsonObject {
                        put("address", sessionAddress(sessionId))
                        put("throughSeq", throughSeq)
                        put("beforeSeq", beforeSeq)
                        put("maxMessages", 500)
                    },
                )
            },
        ) as? JsonObject ?: return HistoryPage(emptyList(), false)
        val events = ((value["records"] as? JsonArray) ?: emptyList()).mapNotNull { (it as? JsonObject)?.obj("event") }
        return HistoryPage(events, value.bool("hasMore") == true)
    }
}

private fun sessionAddress(sessionId: String): JsonObject =
    buildJsonObject {
        put("kind", "session")
        put("sessionId", sessionId)
    }

/* ------------------------------------------------------------------ 界面状态 */

enum class PromptPhase { Sending, Sent, Failed }

sealed interface ChatRow {
    val key: String
}

data class UserRow(
    override val key: String,
    val text: String,
    val time: Long,
    val requestId: String?,
    val phase: PromptPhase,
    val error: String? = null,
    val images: List<MessageImage> = emptyList(),
) : ChatRow

data class AssistantRow(
    override val key: String,
    val text: String,
    val time: Long,
    val streaming: Boolean,
    val interrupted: Boolean = false,
    val images: List<MessageImage> = emptyList(),
    /**
     * 这条消息实际由哪个 provider/model 产出，只认 `assistant/message.data.message.source`。
     * 会话当前的模型选择可能已经变了，不能拿它冒充历史回复的路由。
     */
    val provider: String? = null,
    val model: String? = null,
    val turn: Long? = null,
    val step: Long? = null,
    /** `assistant/message.data.usage` 解析结果；Host 没上报就是 null，不做本地估算。 */
    val usage: TokenUsage? = null,
    /**
     * 同 turn/step 的 `step/start` 到本条消息的墙钟耗时。缺起点（窗口没覆盖到
     * step/start）时为 null；它只是这一步的模型时间，不是整轮耗时。
     */
    val stepDurationMs: Long? = null,
    /** 本步首 token 延迟（`step/start` → 流里第一条非空 delta）；认不出来就是 null。 */
    val ttftMs: Long? = null,
    val reasoning: String = "",
    val reasoningActive: Boolean = false,
) : ChatRow

data class NoticeRow(
    override val key: String,
    val text: String,
    val isError: Boolean,
) : ChatRow

sealed interface SyncPhase {
    data object Idle : SyncPhase
    data object Connecting : SyncPhase
    data object Live : SyncPhase
    data object Reconnecting : SyncPhase
    data class Closed(val message: String) : SyncPhase
    /**
     * [retryable] 为 false 时界面不提供「重新同步」——通道已经断了，同一个传输重试
     * 只会再失败一次，必须回上一层重新连接。
     */
    data class Failed(val message: String, val retryable: Boolean = true) : SyncPhase
}

data class ChatState(
    val sessions: List<SessionSummary> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionsError: String? = null,
    val creating: Boolean = false,
    val createError: String? = null,
    val openSession: SessionSummary? = null,
    val rows: List<ChatRow> = emptyList(),
    val sync: SyncPhase = SyncPhase.Idle,
    val syncWarning: String? = null,
    val running: Boolean = false,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val sendError: String? = null,
    val sending: Boolean = false,
    val title: String? = null,
    val modelCatalog: ModelCatalog? = null,
    val modelCatalogLoading: Boolean = false,
    val modelCatalogError: String? = null,
    /** Host 的会话级选择投影；为空时才退回目录默认模型。 */
    val modelSelection: ModelSelection? = null,
    val modelSwitching: Boolean = false,
    val modelSwitchError: String? = null,
    /**
     * Host 的 `sessionStats` 投影原样保留：`turns / steps / llmMs / toolMs /
     * ttftMs / ttftSteps / decodeMs / decodeTokens`，是整段日志的累计值。
     */
    val sessionStats: JsonObject? = null,
    /** `session/control` 控制流自己的失败；和会话同步、目录读取互相独立。 */
    val modelSyncError: String? = null,
    val approvals: List<ToolApproval> = emptyList(),
    val questions: List<PendingQuestions> = emptyList(),
    val approvalReady: Boolean = false,
    val approvalError: String? = null,
    /**
     * 本地发送成功的计数。界面靠它区分「这条是我刚发的，必须滚到底」和
     * 「远方推来的流式增量，只在用户本来就停在底部时才跟随」。
     */
    val localSendSeq: Int = 0,
)

internal fun analysisStatus(state: ChatState): String? {
    if (!state.running || state.approvals.isNotEmpty() || state.questions.isNotEmpty()) return null
    // 通道彻底关闭或同步失败时不能再报「正在分析」：那会让用户以为模型还在跑。
    if (state.sync is SyncPhase.Closed || state.sync is SyncPhase.Failed) return null
    if (state.rows.any { it is ToolRow && it.phase == ToolPhase.Running }) return null
    when (val last = state.rows.lastOrNull()) {
        is ToolRow -> return null
        is AssistantRow -> if (!last.streaming || (last.text.isNotBlank() && !last.reasoningActive)) return null
        else -> Unit
    }
    val selection = state.modelSelection ?: state.modelCatalog?.default
    val name = selection?.let { state.modelCatalog?.find(it.provider, it.model)?.label ?: readableModelId(it.model) }
    return if (name.isNullOrBlank()) "正在分析" else "${name}正在分析"
}

/**
 * 历史 + 实时 follow 的合并器。
 *
 * 持久事件按 `seq` 去重、排序；缺口先用 `session/page` 补齐，补不齐就重开 follow
 * 拿完整快照，而不是把断档当成完整历史。assistant 流按 revision 连续性接收，
 * 一旦跳号同样重连。发送失败只标记那一条，不伪造“已发送”。
 */
class SessionChatController(
    private val api: SessionApi,
    private val scope: CoroutineScope,
    private val snapshotStore: SessionSnapshotStore? = null,
    initialSnapshotNamespace: String? = null,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /**
     * 切电脑时只换快照键，不重建控制器 —— 聊天记录必须连续。App 层在
     * `connectedHost` 变化后调 [updateSnapshotNamespace]；期间挂在 IO 上的
     * restore 会校验键是否仍然有效，避免把上一台电脑的骨架屏填进来。
     */
    @Volatile
    private var snapshotNamespace: String? = initialSnapshotNamespace

    fun updateSnapshotNamespace(namespace: String?) {
        snapshotNamespace = namespace
    }

    /**
     * Host 是否已回过权威的会话列表。**成功返回空列表也算权威结果**：只看
     * `sessions.isEmpty()` 会把「这台电脑确实没有会话」误判成「还没加载」，
     * 让迟到的旧快照填出一串幽灵会话。
     */
    private val sessionsFromHost = AtomicBoolean(false)

    private val durable = TreeMap<Long, JsonObject>()
    // 持久消息的解析结果只在快照/事件/历史页变化时重建；流式帧只拼接草稿。
    // 否则每个 token 都会重新解析整段历史，ANR 栈会停在 textOf/rebuild。
    private var durableRows: List<ChatRow> = emptyList()
    private val pending = LinkedHashMap<String, UserRow>()
    private val drafts = LinkedHashMap<String, AssistantRow>()
    private val draftSteps = HashMap<String, Pair<Long, Long>>()
    private val draftOrigins = HashMap<String, Long>()
    private var cursor = -1L
    private var followJob: Job? = null
    /** 连接层每次重连成功后自增；follow 续传靠它从「等待重连」被唤醒。 */
    private val transportEpoch = MutableStateFlow(0L)
    private var expectedRevision: Long? = null
    private var hadSnapshot = false
    private var eventsJob: Job? = null
    private var eventClientId: String? = null
    private var sessionGeneration = 0L
    private var modelControlJob: Job? = null
    private var modelCatalogJob: Job? = null

    /**
     * 按会话 id + 投影 key 缓存的 Host 投影值。
     *
     * 控制流是 Host 全局的，baseline 往往早于用户打开任何会话到达；以前直接按
     * 「当前打开的是谁」过滤，这些值就丢了。这里按会话保留，打开会话时直接读取，
     * 切回来也还在。每个 key 单独记自己的水位（Host 的一个事件只改变部分 key），
     * 迟到或重放的帧由水位挡掉。
     */
    private val projectionRows = HashMap<String, MutableMap<String, ProjectionRow>>()

    fun start() {
        restoreSnapshot()
        loadSessions()
        loadModelCatalog()
        followControl()
    }

    /** 冷启动骨架屏：先用上次快照填充列表，[loadSessions] 拿回真实数据后静默覆盖。 */
    private fun restoreSnapshot() {
        val store = snapshotStore ?: return
        scope.launch {
            val namespace = snapshotNamespace ?: return@launch
            val cached = store.read(namespace)?.takeIf { it.isNotEmpty() } ?: return@launch
            // 读盘期间可能已经切了电脑，这份缓存属于上一台，直接丢掉。
            if (namespace != snapshotNamespace) return@launch
            // 只有「Host 还没回过权威列表」才能填骨架屏。成功返回空列表也是权威结果，
            // 不能被旧快照覆盖成幽灵会话；加载失败则仍允许骨架屏兜底。
            if (sessionsFromHost.get() || _state.value.sessions.isNotEmpty()) return@launch
            _state.update { it.copy(sessions = cached) }
        }
    }

    fun loadModelCatalog() {
        if (_state.value.modelCatalogLoading) return
        // 已经成功读到就不再重复请求；失败后调用本方法仍然是有效的重试。
        if (_state.value.modelCatalog != null && _state.value.modelCatalogError == null) return
        modelCatalogJob?.cancel()
        _state.update { it.copy(modelCatalogLoading = true, modelCatalogError = null) }
        modelCatalogJob = scope.launch {
            try {
                val catalog = api.loadModelCatalog()
                _state.update { it.copy(modelCatalog = catalog, modelCatalogLoading = false) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(modelCatalogLoading = false, modelCatalogError = describe(error, "读取模型列表")) }
            }
        }
    }

    /**
     * 主机的投影是权威值：`modelSelection` 决定模型胶囊，`sessionStats` 是整段
     * 日志的累计统计。
     *
     * baseline 覆盖全部会话（可能早于 open 到达，所以按 id 缓存而不是丢弃），
     * projection 帧带 `seq`，只合并 `changes` 里列出的 key。迟到或重放的帧由各
     * key 的水位挡掉，不会把较新的值改回去。控制流失败单独记录在
     * `modelSyncError`，并提供 [retryModelSync] 重新建立，而不是悄悄退化成静态
     * 模型或过期统计。
     */
    private fun followControl() {
        if (modelControlJob?.isActive == true) return
        modelControlJob = scope.launch {
            _state.update { it.copy(modelSyncError = null) }
            try {
                api.followModelSelection().collect { frame ->
                    val map = frame as? JsonObject ?: return@collect
                    when (map.str("type")) {
                        "baseline" -> {
                            val projections = map.obj("value")?.obj("projections") ?: return@collect
                            for ((sessionId, block) in projections) {
                                (block as? JsonObject)?.let { seedProjections(sessionId, it) }
                            }
                        }
                        "projection" -> {
                            val sessionId = map.str("sessionId") ?: return@collect
                            val seq = map.long("seq") ?: return@collect
                            // changes 里没列出的 key 表示没变，不能清空。
                            for (change in map.arr("changes").mapNotNull { it as? JsonObject }) {
                                val key = change.str("key") ?: continue
                                if (key != PROJECTION_MODEL && key != PROJECTION_STATS) continue
                                mergeProjection(sessionId, key, seq, change.obj("val"))
                            }
                        }
                    }
                }
                _state.update { it.copy(modelSyncError = "模型同步已断开，请重新连接") }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // 控制流出错不该清空已经展示的模型和统计；只把失败暴露出来供重试。
                _state.update { it.copy(modelSyncError = describe(error, "模型同步")) }
            }
        }
    }

    /** 重新建立控制流（以及没读到的目录）；已缓存的投影保留，由水位决定谁更新。 */
    fun retryModelSync() {
        modelControlJob?.cancel()
        modelControlJob = null
        _state.update { it.copy(modelSyncError = null) }
        followControl()
        if (_state.value.modelCatalog == null) loadModelCatalog()
    }

    /**
     * 用会话级 baseline 完整替换一个会话的投影。
     *
     * `projections` 块是 `{ asOfSeq, values }`：values 里没有某个 key 表示该水位
     * 上这项能力缺席（要清空）；但 asOfSeq 不比已接受的水位新时整块丢弃，避免晚到
     * 的 follow 快照把更新的控制帧覆盖回去。
     */
    private fun seedProjections(sessionId: String, baseline: JsonObject) {
        val asOfSeq = baseline.long("asOfSeq") ?: Long.MIN_VALUE
        val values = baseline.obj("values")
        val rows = projectionRows.getOrPut(sessionId) { HashMap() }
        for (key in PROJECTION_KEYS) {
            val existing = rows[key]
            if (existing != null && asOfSeq <= existing.seq) continue
            rows[key] = ProjectionRow(values?.obj(key), asOfSeq)
        }
        publishProjections(sessionId)
    }

    private fun mergeProjection(sessionId: String, key: String, seq: Long, value: JsonObject?) {
        val rows = projectionRows.getOrPut(sessionId) { HashMap() }
        val existing = rows[key]
        if (existing != null && seq <= existing.seq) return
        rows[key] = ProjectionRow(value, seq)
        publishProjections(sessionId)
    }

    private fun publishProjections(sessionId: String) {
        if (_state.value.openSession?.id != sessionId) return
        val rows = projectionRows[sessionId]
        val selection = rows?.get(PROJECTION_MODEL)?.value?.let(::parseSelectionProjection)
        val stats = rows?.get(PROJECTION_STATS)?.value
        _state.update {
            if (it.openSession?.id != sessionId) it
            else it.copy(modelSelection = selection, sessionStats = stats, modelSwitchError = null)
        }
    }

    /** 切换失败保留旧值；成功以 Host 确认值更新，随后投影再校正。 */
    fun selectModel(selection: ModelSelection) {
        val session = _state.value.openSession ?: return
        if (_state.value.modelSwitching) return
        // 回包可能在用户切走之后才到；会话代数变了就不能再写进当前界面。
        val generation = sessionGeneration
        _state.update { it.copy(modelSwitching = true, modelSwitchError = null) }
        scope.launch {
            try {
                val confirmed = api.selectModel(session.id, selection)
                if (generation != sessionGeneration || _state.value.openSession?.id != session.id) return@launch
                // 记进缓存时沿用当前水位：真正的投影帧会用更新的事件 seq 校正，
                // 其他 key 的帧也不会把这次已确认的选择顶回旧值。
                val rows = projectionRows.getOrPut(session.id) { HashMap() }
                rows[PROJECTION_MODEL] = ProjectionRow(selectionJson(confirmed), rows[PROJECTION_MODEL]?.seq ?: -1L)
                _state.update { it.copy(modelSelection = confirmed, modelSwitching = false, modelSwitchError = null) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation != sessionGeneration || _state.value.openSession?.id != session.id) return@launch
                _state.update { it.copy(modelSwitching = false, modelSwitchError = describe(error, "切换模型")) }
            }
        }
    }


    fun loadSessions() {
        if (_state.value.sessionsLoading) return
        // 首次加载（列表还空着）才亮 loading 骨架；之后的定时刷新静默进行——
        // 不然抽屉里每 20 秒就闪一次加载动画，用户会看到列表"一直在刷"。
        val firstLoad = _state.value.sessions.isEmpty()
        _state.update { it.copy(sessionsLoading = firstLoad, sessionsError = null) }
        scope.launch {
            try {
                val sessions = api.listSessions()
                sessionsFromHost.set(true)
                _state.update { it.copy(sessions = sessions, sessionsLoading = false) }
                persistSnapshot(sessions)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(sessionsLoading = false, sessionsError = describe(error, "读取会话列表")) }
            }
        }
    }

    /** 列表加载成功后落快照；写失败不影响主流程（只是下次冷启动少个骨架屏）。 */
    private suspend fun persistSnapshot(sessions: List<SessionSummary>) {
        val store = snapshotStore ?: return
        val namespace = snapshotNamespace ?: return
        runCatching { store.write(namespace, sessions) }
    }

    /**
     * 新建一个独立会话并立刻打开它。
     *
     * 只用 Host 返回的 `sessionId`；列表刷新失败也不影响打开，列表条目只是占位标题，
     * 真正的标题来自 follow 快照的 projections。创建失败时绝不退回到某个已有会话。
     *
     * [onOpened] 是给导航层用的：会话 id 由 Host 异步返回，调用方拿不到，只能在
     * 打开成功的那一刻把 summary 交回去，界面才知道该跳到哪一页。
     */
    fun createSession(cwd: String? = null, onOpened: ((SessionSummary) -> Unit)? = null) {
        if (_state.value.creating) return
        _state.update { it.copy(creating = true, createError = null) }
        scope.launch {
            try {
                val id = api.create(cwd)
                val sessions = runCatching { api.listSessions() }.getOrNull()
                _state.update { it.copy(creating = false, sessions = sessions ?: it.sessions) }
                val summary = sessions?.firstOrNull { it.id == id }
                    ?: SessionSummary(
                        id = id,
                        title = null,
                        updatedAt = System.currentTimeMillis(),
                        running = false,
                        cwd = cwd,
                        origin = null,
                    )
                open(summary)
                onOpened?.invoke(summary)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(creating = false, createError = describe(error, "新建会话")) }
            }
        }
    }

    fun open(session: SessionSummary) {
        retireApprovals()
        followJob?.cancel()
        durable.clear()
        durableRows = emptyList()
        pending.clear()
        clearDrafts()
        cursor = -1
        expectedRevision = null
        hadSnapshot = false
        val cached = projectionRows[session.id]
        _state.update {
            it.copy(
                openSession = session,
                rows = emptyList(),
                title = session.title,
                // 控制流多半早于这次 open 到过；按 id 取回，跨会话切换也能立刻显示。
                modelSelection = cached?.get(PROJECTION_MODEL)?.value?.let(::parseSelectionProjection),
                sessionStats = cached?.get(PROJECTION_STATS)?.value,
                modelSwitching = false,
                modelSwitchError = null,
                sync = SyncPhase.Connecting,
                syncWarning = null,
                running = session.running,
                hasMore = false,
                loadingMore = false,
                sendError = null,
                createError = null,
            )
        }
        follow(session)
        followApprovals(session)
    }

    fun closeSession() {
        retireApprovals()
        followJob?.cancel()
        followJob = null
        durable.clear()
        durableRows = emptyList()
        pending.clear()
        clearDrafts()
        cursor = -1
        hadSnapshot = false
        _state.update {
            it.copy(openSession = null, rows = emptyList(), sync = SyncPhase.Idle, syncWarning = null,
                running = false, modelSelection = null, sessionStats = null,
                modelSwitching = false, modelSwitchError = null)
        }
    }

    fun retry() {
        val session = _state.value.openSession ?: return
        open(session)
    }

    /**
     * 连接层重连成功后，让会话层无缝续传，而不是整页重载。
     *
     * 断线时 [follow] 不会退出，而是停在 [transportEpoch] 上等下一次换代；这里切到
     * 新隧道、自增代数唤醒它，并把同样断掉的控制流（模型/统计投影）与审批流一并
     * 重建。已加载的聊天记录（durable/rows）原样保留，续传快照会按 seq 幂等合并。
     */
    fun onTransportReconnected(transport: HarnessTransport) {
        api.switchTransport(transport)
        transportEpoch.value += 1
        // 控制流与审批流也是 stream，通道一断同样停掉；重连后要各自重建。
        retryModelSync()
        eventsJob?.cancel()
        _state.value.openSession?.let { followApprovals(it) }
        // 断线期间 Host 侧可能新建/关闭过会话，重连后静默刷新一次列表。
        loadSessions()
    }

    private fun follow(session: SessionSummary) {
        followJob = scope.launch {
            var resyncs = 0
            while (true) {
                val epochAtStart = transportEpoch.value
                try {
                    _state.update {
                        it.copy(
                            sync = if (resyncs == 0) SyncPhase.Connecting else SyncPhase.Reconnecting,
                            syncWarning = null,
                        )
                    }
                    api.follow(session.id).collect { frame -> applyFrame(session, frame) }
                    if (_state.value.openSession?.id == session.id) {
                        _state.update { it.copy(sync = SyncPhase.Closed("Host 结束了实时同步"), running = false) }
                    }
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (gap: SyncGap) {
                    resyncs++
                    if (resyncs > 3) {
                        _state.update { it.copy(sync = SyncPhase.Failed("实时同步反复断档，请重试"), running = false) }
                        return@launch
                    }
                    _state.update { it.copy(sync = SyncPhase.Reconnecting, syncWarning = gap.message) }
                    delay(300)
                } catch (error: Throwable) {
                    if (isTransportClosed(error)) {
                        // 通道断了：保留已加载的聊天记录，等连接层重连后续传，而不是
                        // 退出循环、让用户手动「重新同步」。重连若在等待期间已完成，
                        // epoch 已经变了，立即续传；否则挂起等下一次换代。
                        resyncs = 0
                        if (_state.value.openSession?.id == session.id) {
                            _state.update { it.copy(sync = SyncPhase.Reconnecting, syncWarning = null) }
                        }
                        transportEpoch.filter { it != epochAtStart }.first()
                        continue
                    }
                    if (_state.value.openSession?.id == session.id) {
                        _state.update {
                            it.copy(
                                sync = SyncPhase.Failed(
                                    syncFailure(error, "实时同步"),
                                    retryable = !isTransportClosed(error),
                                ),
                                running = false,
                            )
                        }
                    }
                    return@launch
                }
            }
        }
    }

    suspend fun loadImage(image: MessageImage): ByteArray {
        val session = _state.value.openSession ?: throw AttachmentException("会话已关闭")
        return api.loadImage(session.id, image)
    }

    /** T15：按工作区相对路径读图，供 markdown 里的 ![](path) 使用。 */
    suspend fun loadWorkspaceImage(path: String): ByteArray {
        val session = _state.value.openSession ?: throw AttachmentException("会话已关闭")
        return api.loadWorkspaceImage(session.id, path)
    }

    fun send(text: String, images: List<PromptImage> = emptyList()): Boolean {
        val session = _state.value.openSession ?: return false
        val body = text.trim()
        if (body.isEmpty() && images.isEmpty()) return false
        val requestId = UUID.randomUUID().toString()
        pending[requestId] = UserRow(
            key = "p$requestId",
            text = body,
            time = System.currentTimeMillis(),
            requestId = requestId,
            phase = PromptPhase.Sending,
            images = images.map { it.preview() },
        )
        rebuild()
        _state.update { it.copy(sending = true, sendError = null, localSendSeq = it.localSendSeq + 1) }
        scope.launch {
            try {
                // Host 返回的 requestId 必须与本地一致；以 Host 的 echoed id 归并更稳。
                api.prompt(session.id, body, requestId, images)
                pending[requestId] = pending[requestId]?.copy(phase = PromptPhase.Sent) ?: return@launch
                _state.update { it.copy(sending = pending.values.any { p -> p.phase == PromptPhase.Sending }) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = syncFailure(error, "发送")
                pending[requestId]?.let { pending[requestId] = it.copy(phase = PromptPhase.Failed, error = message) }
                _state.update {
                    // 通道断了就不要再显示「实时同步中」——那会让用户以为只是这一条发失败。
                    if (it.openSession?.id == session.id && isTransportClosed(error)) {
                        it.copy(
                            sending = false,
                            sendError = null,
                            running = false,
                            sync = SyncPhase.Failed(message, retryable = false),
                        )
                    } else {
                        it.copy(sending = false, sendError = message)
                    }
                }
            }
            rebuild()
        }
        return true
    }

    /**
     * 重发一条**发送失败**的提问。
     *
     * 这种失败意味着 prompt 根本没送到 Host，所以先把本地那条失败行摘掉再重发：
     * 否则会同时留下「发送失败」和「发送中」两个气泡，看起来像发了两遍。
     * 图片随行一起带回——[MessageImage.data] 保留着原始 base64，不必让用户重选。
     */
    fun resend(row: UserRow): Boolean {
        // 连点两下会发两遍：`send` 本身不拦并发，守卫放在入口。
        if (_state.value.sending || _state.value.running) return false
        row.requestId?.let { pending.remove(it) }
        rebuild()
        return send(row.text, row.images.toPromptImages())
    }

    /**
     * 重试最近一轮，用于「本轮失败」「输出被中断」这两类**已经发出去**的失败。
     *
     * 核心层没有 `session/retry`（RPC 只有 prompt / cancel / job.kill），只能把上一条
     * 用户提问原样再发一次。代价是会话里会多一轮消息：这一轮的上下文已经消耗掉了，
     * 不复用旧 turn 才不会凭空捏造一个 Host 侧不存在的重试语义。
     */
    fun retryLastTurn(): Boolean {
        val state = _state.value
        // 正在发/正在跑时重试只会多插一轮，先在入口挡住。
        if (state.sending || state.running) return false
        val lastUser = state.rows.lastOrNull { it is UserRow } as? UserRow ?: return false
        return send(lastUser.text, lastUser.images.toPromptImages())
    }

    fun cancelTurn() {
        val session = _state.value.openSession ?: return
        scope.launch {
            try {
                api.cancel(session.id)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(sendError = describe(error, "取消")) }
            }
        }
    }

    fun loadOlder() {
        val session = _state.value.openSession ?: return
        val oldest = durable.firstKey() ?: return
        if (_state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            try {
                val page = api.history(session.id, throughSeq = maxOf(cursor, oldest), beforeSeq = oldest)
                for (event in page.events) mergeEvent(event)
                _state.update { it.copy(loadingMore = false, hasMore = page.hasMore) }
                rebuild()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _state.update { it.copy(loadingMore = false, syncWarning = describe(error, "加载更早消息")) }
            }
        }
    }

    private fun retireApprovals() {
        sessionGeneration++
        eventsJob?.cancel()
        eventsJob = null
        eventClientId = null
        _state.update { it.copy(approvals = emptyList(), questions = emptyList(), approvalReady = false, approvalError = null) }
    }

    private fun followApprovals(session: SessionSummary) {
        val generation = sessionGeneration
        eventsJob = scope.launch {
            try {
                api.hostEvents().collect { frame ->
                    if (generation != sessionGeneration) return@collect
                    val map = frame as? JsonObject ?: return@collect
                    when (map.str("type")) {
                        "ready" -> {
                            eventClientId = map.str("clientId")?.takeIf { it.isNotBlank() }
                            _state.update { it.copy(approvals = emptyList(), questions = emptyList(), approvalReady = eventClientId != null, approvalError = null) }
                        }
                        "waterfall" -> {
                            if (eventClientId == null || map.str("agentId") != session.id) return@collect
                            val id = map.str("eventId")?.takeIf { it.isNotBlank() } ?: return@collect
                            val request = map.obj("request") ?: return@collect
                            when (map.str("event")) {
                                "approval/request" -> {
                                    val display = request.obj("displayReason")
                                    val reason = listOf("zh-CN", "zh-Hans", "zh", "en").firstNotNullOfOrNull { display?.str(it) }
                                        ?: request.str("reason")
                                    val approval = ToolApproval(id, session.id, request.str("toolName") ?: "工具", request.str("callId"), reason)
                                    _state.update { state ->
                                        if (state.approvals.any { it.eventId == id }) state else state.copy(approvals = state.approvals + approval)
                                    }
                                }
                                "user-questions/request" -> {
                                    // 模型调了 ask_user_question 就停在这里等人回答。之前这条事件被忽略，
                                    // 手机上只看到一张「执行中」的工具卡，用户既不知道在等自己，也没地方答。
                                    val asked = (request["questions"] as? JsonArray)?.toList().orEmpty().mapNotNull { part ->
                                        val item = part as? JsonObject ?: return@mapNotNull null
                                        val questionId = item.str("id") ?: return@mapNotNull null
                                        val text = item.str("question") ?: return@mapNotNull null
                                        AskQuestion(
                                            id = questionId,
                                            question = text,
                                            header = item.str("header")?.takeIf { it.isNotBlank() },
                                            options = (item["options"] as? JsonArray)?.toList().orEmpty().mapNotNull { option ->
                                                val entry = option as? JsonObject ?: return@mapNotNull null
                                                val label = entry.str("label") ?: return@mapNotNull null
                                                AskOption(label, entry.str("description")?.takeIf { it.isNotBlank() })
                                            },
                                            multiSelect = item.bool("multiSelect") == true,
                                        )
                                    }
                                    if (asked.isEmpty()) return@collect
                                    val pendingQuestions = PendingQuestions(
                                        eventId = id,
                                        callId = request.obj("wait")?.str("callId") ?: request.str("callId"),
                                        questions = asked,
                                    )
                                    _state.update { state ->
                                        if (state.questions.any { it.eventId == id }) state
                                        else state.copy(questions = state.questions + pendingQuestions)
                                    }
                                }
                            }
                        }
                        "cancel" -> _state.update { state ->
                            state.copy(
                                approvals = state.approvals.filterNot { it.eventId == map.str("eventId") },
                                questions = state.questions.filterNot { it.eventId == map.str("eventId") },
                            )
                        }
                    }
                }
                if (generation == sessionGeneration) {
                    eventClientId = null
                    _state.update { it.copy(approvals = emptyList(), approvalReady = false, approvalError = "审批通道已断开，请重新同步") }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation == sessionGeneration) {
                    eventClientId = null
                    _state.update { it.copy(approvals = emptyList(), approvalReady = false, approvalError = describe(error, "审批同步")) }
                }
            }
        }
    }

    fun respondApproval(eventId: String, decision: ApprovalDecision) {
        val clientId = eventClientId ?: return
        val approval = _state.value.approvals.firstOrNull { it.eventId == eventId } ?: return
        if (approval.submitting || approval.sessionId != _state.value.openSession?.id || !_state.value.approvalReady) return
        val generation = sessionGeneration
        _state.update { state -> state.copy(approvals = state.approvals.map { if (it.eventId == eventId) it.copy(submitting = true, error = null) else it }) }
        scope.launch {
            try {
                api.respondApproval(clientId, eventId, decision)
                if (generation == sessionGeneration && clientId == eventClientId) {
                    _state.update { state -> state.copy(approvals = state.approvals.filterNot { it.eventId == eventId }) }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation == sessionGeneration && clientId == eventClientId) {
                    _state.update { state -> state.copy(approvals = state.approvals.map {
                        if (it.eventId == eventId) it.copy(submitting = false, error = describe(error, "审批")) else it
                    }) }
                }
            }
        }
    }

    /**
     * 勾选/取消一个选项。单选题换选即覆盖，多选题累加。
     * 单选再点一次已选项要能取消，否则用户选错了没法退回去。
     */
    fun toggleOption(eventId: String, questionId: String, label: String) {
        _state.update { state ->
            state.copy(questions = state.questions.map { pending ->
                if (pending.eventId != eventId) return@map pending
                val question = pending.questions.firstOrNull { it.id == questionId } ?: return@map pending
                val current = pending.selected[questionId].orEmpty()
                val next = when {
                    !question.multiSelect -> if (current.contains(label)) emptySet() else setOf(label)
                    current.contains(label) -> current - label
                    else -> current + label
                }
                pending.copy(selected = pending.selected + (questionId to next), error = null)
            })
        }
    }

    fun setQuestionDraft(eventId: String, questionId: String, text: String) {
        _state.update { state ->
            state.copy(questions = state.questions.map { pending ->
                if (pending.eventId != eventId) pending
                else pending.copy(drafts = pending.drafts + (questionId to text), error = null)
            })
        }
    }

    /** 整批提交：没选的题按协议要求给空 selected，表示用户明确跳过这一题。 */
    fun submitAnswers(eventId: String) {
        val clientId = eventClientId ?: return
        val pending = _state.value.questions.firstOrNull { it.eventId == eventId } ?: return
        if (pending.submitting) return
        val generation = sessionGeneration
        _state.update { state -> state.copy(questions = state.questions.map { if (it.eventId == eventId) it.copy(submitting = true, error = null) else it }) }
        scope.launch {
            try {
                api.respondEvent(clientId, eventId, buildJsonObject {
                    put("answers", buildJsonArray {
                        pending.questions.forEach { question ->
                            add(buildJsonObject {
                                put("id", question.id)
                                put("selected", buildJsonArray {
                                    pending.selected[question.id].orEmpty().forEach { add(JsonPrimitive(it)) }
                                })
                                val custom = pending.drafts[question.id]?.trim().orEmpty()
                                if (custom.isNotEmpty()) put("custom", custom)
                            })
                        }
                    })
                })
                if (generation == sessionGeneration && clientId == eventClientId) {
                    _state.update { state -> state.copy(questions = state.questions.filterNot { it.eventId == eventId }) }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation == sessionGeneration && clientId == eventClientId) {
                    _state.update { state -> state.copy(questions = state.questions.map {
                        if (it.eventId == eventId) it.copy(submitting = false, error = describe(error, "回答")) else it
                    }) }
                }
            }
        }
    }

    fun stop() {
        retireApprovals()
        followJob?.cancel()
        followJob = null
        modelControlJob?.cancel()
        modelControlJob = null
        modelCatalogJob?.cancel()
        modelCatalogJob = null
    }

    /* --------------------------------------------------------------- frames */

    private suspend fun applyFrame(session: SessionSummary, frame: JsonElement) {
        val map = frame as? JsonObject ?: throw SyncGap("Host 返回了非对象的同步帧")
        when (map.str("type")) {
            "snapshot" -> applySnapshot(session, map)
            "event" -> {
                val event = map.obj("event") ?: throw SyncGap("事件帧缺少 event")
                acceptEvent(session.id, event, requireSnapshot = true)
            }
            "assistant-stream" -> {
                if (!hadSnapshot) throw SyncGap("assistant 流早于快照")
                val assistant = map.obj("frame") ?: throw SyncGap("assistant 帧缺少 frame")
                applyAssistant(assistant)
            }
            else -> Unit
        }
    }

    private fun applySnapshot(session: SessionSummary, snapshot: JsonObject) {
        val records = ((snapshot["records"] as? JsonArray) ?: emptyList()).mapNotNull {
            (it as? JsonObject)?.obj("event")
        }
        val retain = hadSnapshot
        if (!retain) durable.clear()
        for (event in records) mergeEvent(event)
        cursor = maxOf(cursor, snapshot.long("cursor") ?: -1)
        hadSnapshot = true
        // 每次快照都以 Host 为准重建草稿；进行中的 attempt 已带上截至此刻的全部 chunk。
        clearDrafts()
        val stream = snapshot.obj("assistantStream")
        expectedRevision = stream?.long("revision")
        stream?.obj("activeAttempt")?.let { restoreActiveAttempt(it) }
        val title = snapshot.obj("projections")?.obj("values")?.str("title")
        // follow 快照的 projections 也是 `{ asOfSeq, values }`，与控制帧共用同一套
        // 水位：更旧的快照不会覆盖已经接受的较新控制帧。
        snapshot.obj("projections")?.let { seedProjections(session.id, it) }
        _state.update {
            it.copy(
                sync = SyncPhase.Live,
                syncWarning = null,
                title = title ?: it.title,
                hasMore = if (retain) it.hasMore else snapshot.bool("hasMore") == true,
                running = deriveRunning(),
            )
        }
        rebuild()
    }

    private fun restoreActiveAttempt(active: JsonObject) {
        val attemptId = active.str("attemptId")?.takeIf { it.isNotBlank() } ?: return
        val turn = active.long("turn") ?: 0
        val step = active.long("step") ?: 0
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var reasoningActive = false
        for (record in (active["stream"] as? JsonArray) ?: emptyList()) {
            val map = record as? JsonObject ?: continue
            when (map.str("type")) {
                "chunk" -> {
                    val chunk = map.obj("chunk")
                    when (chunk?.str("type")) {
                        "text-delta" -> { text.append(chunk.str("text") ?: ""); reasoningActive = false }
                        "reasoning-delta" -> { reasoning.append(chunk.str("text") ?: ""); reasoningActive = true }
                    }
                }
                "text-chunks" -> {
                    ((map["texts"] as? JsonArray) ?: emptyList()).forEach { part ->
                        text.append((part as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "")
                    }
                    reasoningActive = false
                }
                // 桌面端会把同一批 delta 压成 reasoning-chunks，成员同样在 texts 里；
                // 不解析会在断线重连后整段丢掉已经收到的思考过程。
                "reasoning-chunks" -> {
                    ((map["texts"] as? JsonArray) ?: emptyList()).forEach { part ->
                        reasoning.append((part as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "")
                    }
                    reasoningActive = true
                }
            }
        }
        drafts[attemptId] = AssistantRow("m$turn.$step", text.toString(), now(), streaming = true,
            turn = turn, step = step, reasoning = reasoning.toString(), reasoningActive = reasoningActive)
        draftSteps[attemptId] = turn to step
        draftOrigins[attemptId] = active.long("startedAfterSeq") ?: -1
    }

    private suspend fun acceptEvent(sessionId: String, event: JsonObject, requireSnapshot: Boolean) {
        if (requireSnapshot && !hadSnapshot) throw SyncGap("事件早于快照")
        val seq = event.long("seq") ?: throw SyncGap("事件缺少 seq")
        if (seq <= cursor) return
        if (seq > cursor + 1) {
            fillGap(sessionId, from = cursor + 1, before = seq, through = seq - 1)
            var expected = cursor + 1
            while (durable.containsKey(expected)) expected++
            if (expected != seq) {
                throw SyncGap("事件序号从 $expected 跳到 $seq，历史没有补全")
            }
            cursor = seq - 1
        }
        mergeEvent(event)
        cursor = seq
        _state.update { it.copy(running = deriveRunning()) }
        rebuild()
    }

    /** 缺口可能超过一页；持续按 `beforeSeq` 向前翻，直到接上 [from]。 */
    private suspend fun fillGap(sessionId: String, from: Long, before: Long, through: Long) {
        var beforeSeq = before
        while (beforeSeq > from) {
            val page = api.history(sessionId, throughSeq = through, beforeSeq = beforeSeq)
            val usable = page.events.filter { event ->
                val seq = event.long("seq") ?: return@filter false
                seq in from until beforeSeq
            }.sortedBy { it.long("seq") }
            if (usable.isEmpty()) return
            usable.forEach(::mergeEvent)
            beforeSeq = usable.first().long("seq") ?: return
            if (!page.hasMore) return
        }
    }

    private fun mergeEvent(event: JsonObject) {
        val seq = event.long("seq") ?: return
        durable[seq] = event
        val type = event.str("type")
        val data = event.obj("data") ?: JsonObject(emptyMap())
        if (type == "user/message") {
            val message = data.obj("message") ?: data
            val rpcId = message.obj("source")?.str("rpcId")
            if (rpcId != null) pending.remove(rpcId)
        }
        if (type == "assistant/message" || type == "assistant/attempt") {
            val turn = data.long("turn")
            val step = data.long("step")
            if (turn != null && step != null) dropDrafts(turn, step, seq)
        }
    }

    /** 落盘消息只替换它之前开始的草稿；同一步里更晚的 attempt 继续保留。 */
    private fun dropDrafts(turn: Long, step: Long, seq: Long) {
        val stale = drafts.keys.filter { attemptId ->
            draftSteps[attemptId] == turn to step && (draftOrigins[attemptId] ?: -1) < seq
        }
        for (attemptId in stale) {
            drafts.remove(attemptId)
            draftSteps.remove(attemptId)
            draftOrigins.remove(attemptId)
        }
    }

    private fun clearDrafts() {
        drafts.clear()
        draftSteps.clear()
        draftOrigins.clear()
    }

    private fun applyAssistant(frame: JsonObject) {
        val attemptId = frame.str("attemptId") ?: throw SyncGap("assistant 帧缺少 attemptId")
        val revision = frame.long("revision") ?: throw SyncGap("assistant 帧缺少 revision")
        val expected = (expectedRevision ?: 0) + 1
        if (revision != expected) throw SyncGap("assistant 流跳号：期望 $expected，收到 $revision")
        expectedRevision = revision
        val turn = frame.long("turn") ?: 0
        val step = frame.long("step") ?: 0
        val key = "m$turn.$step"
        when (frame.str("type")) {
            "start" -> {
                drafts[attemptId] = AssistantRow(
                    key, "", now(), true,
                    turn = frame.long("turn"), step = frame.long("step"),
                )
                draftSteps[attemptId] = turn to step
                draftOrigins[attemptId] = frame.long("startedAfterSeq") ?: -1
            }
            "chunk" -> {
                val draft = drafts[attemptId] ?: AssistantRow(
                    key, "", now(), true,
                    turn = frame.long("turn"), step = frame.long("step"),
                ).also {
                    drafts[attemptId] = it
                    draftSteps[attemptId] = turn to step
                    draftOrigins[attemptId] = -1
                }
                val chunk = frame.obj("chunk")
                when (chunk?.str("type")) {
                    "text-delta" -> drafts[attemptId] = draft.copy(text = draft.text + (chunk.str("text") ?: ""), streaming = true, reasoningActive = false)
                    "reasoning-delta" -> drafts[attemptId] = draft.copy(reasoning = draft.reasoning + (chunk.str("text") ?: ""), streaming = true, reasoningActive = true)
                }
            }
            "end" -> {
                val draft = drafts[attemptId] ?: return
                val outcome = frame.obj("outcome")
                val committed = outcome?.str("kind") == "committed"
                val committedSeq = outcome?.long("seq")
                if (!committed || (committedSeq != null && durable.containsKey(committedSeq))) {
                    // 未提交的 attempt 没有对应消息；已落盘的则交给 durable 行。
                    drafts.remove(attemptId)
                    draftSteps.remove(attemptId)
                    draftOrigins.remove(attemptId)
                } else {
                    drafts[attemptId] = draft.copy(streaming = false)
                }
            }
        }
        // 流式帧只改变草稿，不触碰 durable；复用持久行解析结果。
        publishRows()
    }

    private fun deriveRunning(): Boolean {
        var running = false
        for (event in durable.values) {
            when (event.str("type")) {
                "turn/start" -> running = true
                "turn/end" -> running = false
            }
        }
        return running
    }

    private fun rebuild() {
        durableRows = projectDurableRows()
        publishRows()
    }

    private fun publishRows() {
        val rows = ArrayList<ChatRow>(durableRows.size + drafts.size + pending.size)
        rows.addAll(durableRows)
        rows += drafts.values.filter { it.text.isNotBlank() || it.reasoning.isNotBlank() }
        rows += pending.values
        _state.update { it.copy(rows = rows) }
    }

    private fun projectDurableRows(): List<ChatRow> {
        val rows = ArrayList<ChatRow>()
        val tools = projectTools(durable.values)
        /** 已进入、但还没组装出消息的步骤起点；只用于算这一步的墙钟耗时。 */
        val stepStarts = HashMap<Pair<Long, Long>, Long>()
        for (event in durable.values) {
            val seq = event.long("seq") ?: continue
            tools[seq]?.let { rows += it }
            val time = event.long("time") ?: 0
            val data = event.obj("data") ?: JsonObject(emptyMap())
            when (event.str("type")) {
                "step/start" -> {
                    val turn = data.long("turn")
                    val step = data.long("step")
                    if (turn != null && step != null) stepStarts[turn to step] = time
                }
                "user/message" -> {
                    val message = data.obj("message") ?: data
                    val text = textOf(message)
                    val images = messageImages(message)
                    if (text.isBlank() && images.isEmpty()) continue
                    val synthetic = message.obj("source")?.str("kind")
                    if (synthetic != null && synthetic != "user") continue
                    rows += UserRow(
                        key = "u$seq",
                        text = text,
                        time = time,
                        requestId = message.obj("source")?.str("rpcId"),
                        phase = PromptPhase.Sent,
                        images = images,
                    )
                }
                "assistant/message" -> {
                    val message = data.obj("message") ?: JsonObject(emptyMap())
                    val text = textOf(message)
                    val images = messageImages(message)
                    if (text.isBlank() && images.isEmpty()) continue
                    val turn = data.long("turn")
                    val step = data.long("step")
                    // 路由只信这条消息自己的 source；usage 按 TokenUsage 解析。
                    val source = message.obj("source")
                    val startedAt = if (turn != null && step != null) stepStarts.remove(turn to step) else null
                    val firstToken = firstTokenTime(data["stream"] as? JsonArray)
                    rows += AssistantRow(
                        key = "a$seq",
                        text = text,
                        time = time,
                        streaming = false,
                        interrupted = data.bool("interrupted") == true,
                        images = images,
                        provider = source?.str("provider")?.takeIf { it.isNotBlank() },
                        model = source?.str("model")?.takeIf { it.isNotBlank() },
                        turn = turn,
                        step = step,
                        usage = parseUsage(data.obj("usage")),
                        stepDurationMs = startedAt?.let { start -> (time - start).takeIf { it >= 0 } },
                        ttftMs = startedAt?.let { start -> firstToken?.let { (it - start).takeIf { ms -> ms >= 0 } } },
                        reasoning = ((message["content"] as? JsonArray) ?: emptyList()).mapNotNull { part ->
                            (part as? JsonObject)?.takeIf { it.str("type") == "reasoning" }?.str("text")
                        }.joinToString("\n"),
                    )
                }
                "turn/end" -> {
                    val reason = data.obj("reason")
                    when (val kind = reason?.str("kind") ?: "unknown") {
                        "aborted" -> rows += NoticeRow("n$seq", "本轮已停止", isError = false)
                        "error" -> {
                            val failure = reason?.obj("error")?.str("message")
                            rows += NoticeRow("n$seq", "本轮失败${failure?.let { "：$it" } ?: ""}", isError = true)
                        }
                        "max-tokens" -> rows += NoticeRow("n$seq", "本轮达到输出长度上限", isError = false)
                        "blocked" -> rows += NoticeRow("n$seq", "本轮被阻止", isError = true)
                        "interrupted" -> rows += NoticeRow("n$seq", "本轮被中断（Host 重启）", isError = false)
                        else -> Unit
                    }
                }
            }
        }
        return rows
    }

    private fun textOf(message: JsonObject): String {
        val content = message["content"] ?: return message.str("text") ?: ""
        if (content is JsonPrimitive) return content.contentOrNullSafe()
        val parts = content as? JsonArray ?: return ""
        return parts.mapNotNull { part ->
            val map = part as? JsonObject ?: return@mapNotNull null
            if (map.str("type") == "text") map.str("text") else null
        }.joinToString("")
    }

    private fun now(): Long = System.currentTimeMillis()

    private class SyncGap(message: String) : Exception(message)
}

/** 客户端跟踪的两项会话投影；其余 key 不参与界面，直接忽略。 */
private const val PROJECTION_MODEL = "modelSelection"
private const val PROJECTION_STATS = "sessionStats"
private val PROJECTION_KEYS = arrayOf(PROJECTION_MODEL, PROJECTION_STATS)

/** 一个会话某个投影 key 的现值，以及这段值对应的权威水位。 */
private data class ProjectionRow(val value: JsonObject?, val seq: Long)

/** 把已确认的选择还原成投影值，写回缓存时沿用同样的 wire 形状。 */
private fun selectionJson(selection: ModelSelection): JsonObject = buildJsonObject {
    put("provider", selection.provider)
    put("model", selection.model)
    selection.reasoningEffort?.let { put("reasoningEffort", it) }
}

private fun describe(error: Throwable, what: String): String = when (error) {
    is HarnessException -> "${what}失败：${error.message}（${error.code}）"
    is RemoteRpcException -> "${what}失败：${error.message}（${error.code}）"
    else -> "${what}失败：${error.message ?: error.javaClass.simpleName}"
}

/** 通道级关闭：本侧判定，不需要等 Host 回应，重试同一个传输也不会有结果。 */
private fun isTransportClosed(error: Throwable): Boolean =
    error is RemoteRpcException && error.isLocal &&
        (error.code == "TRANSPORT_CLOSED" || error.code == "CLIENT_CLOSED")

private fun syncFailure(error: Throwable, what: String): String =
    if (isTransportClosed(error)) "与电脑的连接已断开，请返回上一页重新连接这台电脑。" else describe(error, what)

/**
 * 把界面上已经渲染过的图片还原成可以再次提交的形态，供「重试」「重新发送」使用。
 *
 * 只认还带着 base64 的那些：走 attachmentId 引用的图片内容是 Host 侧的，本机没有正本，
 * 硬凑一个空 data 只会让 Host 收到一张坏图，所以这类图片在重发时丢弃（文字仍会发出去）。
 */
private fun List<MessageImage>.toPromptImages(): List<PromptImage> = mapNotNull { image ->
    val data = image.data?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    PromptImage(
        name = image.name,
        mediaType = image.mediaType ?: "image/png",
        data = data,
        bytes = data.length / 4 * 3,
    )
}

