package com.xxt.zl.ai.ruyiremote.ui.screen

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.session.ChatState
import com.xxt.zl.ai.ruyiremote.session.AssistantRow
import com.xxt.zl.ai.ruyiremote.session.analysisStatus
import com.xxt.zl.ai.ruyiremote.session.ModelCatalog
import com.xxt.zl.ai.ruyiremote.session.ModelEffort
import com.xxt.zl.ai.ruyiremote.session.ModelSelection
import com.xxt.zl.ai.ruyiremote.session.TokenUsage
import com.xxt.zl.ai.ruyiremote.session.formatElapsed
import com.xxt.zl.ai.ruyiremote.session.formatTokenCount
import com.xxt.zl.ai.ruyiremote.session.readableModelId
import com.xxt.zl.ai.ruyiremote.session.MessageImage
import com.xxt.zl.ai.ruyiremote.session.NoticeRow
import com.xxt.zl.ai.ruyiremote.session.PromptImage
import com.xxt.zl.ai.ruyiremote.session.PromptPhase
import com.xxt.zl.ai.ruyiremote.session.SessionChatController
import com.xxt.zl.ai.ruyiremote.session.SessionSummary
import com.xxt.zl.ai.ruyiremote.session.SyncPhase
import com.xxt.zl.ai.ruyiremote.session.ToolPhase
import com.xxt.zl.ai.ruyiremote.session.ToolRow
import com.xxt.zl.ai.ruyiremote.session.UploadedFile
import com.xxt.zl.ai.ruyiremote.session.UserRow
import com.xxt.zl.ai.ruyiremote.session.WorkspaceUploader
import com.xxt.zl.ai.ruyiremote.session.decodeImageData
import com.xxt.zl.ai.ruyiremote.session.quoteIntoDraft
import com.xxt.zl.ai.ruyiremote.session.uploadedFilesPrompt
import com.xxt.zl.ai.ruyiremote.ui.components.AttachmentImagePreview
import com.xxt.zl.ai.ruyiremote.ui.components.AttachmentPicker
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphBack
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphMenu
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphMore
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphChevron
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphCheckCircle
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphCopy
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphPlus
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphSend
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphShare
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphSpark
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphStop
import com.xxt.zl.ai.ruyiremote.ui.components.InlineError
import com.xxt.zl.ai.ruyiremote.ui.components.MarkdownContent
import com.xxt.zl.ai.ruyiremote.ui.components.NoticeBar
import com.xxt.zl.ai.ruyiremote.ui.components.QuestionCard
import com.xxt.zl.ai.ruyiremote.ui.components.ToolApprovalCard
import com.xxt.zl.ai.ruyiremote.ui.components.ToolCallCard
import com.xxt.zl.ai.ruyiremote.ui.components.ToolSummaryLine
import com.xxt.zl.ai.ruyiremote.ui.components.TypingDots
import com.xxt.zl.ai.ruyiremote.ui.formatWhen
import com.xxt.zl.ai.ruyiremote.ui.screenFrame
import com.xxt.zl.ai.ruyiremote.ui.theme.RuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 对话页。
 *
 * 视觉基调是「一张浅蓝的纸，上面漂着几个白色胶囊」：页面背景是竖向渐变，
 * 顶栏与输入栏都不是实心横条，而是浮在纸上的圆角块。这样滚动时内容从胶囊
 * 下面穿过去，页面看起来是连续的，而不是被切成一格一格的。
 *
 * 消息分两类呈现：
 * - 用户右对齐、软蓝气泡。自己说的话是「一整块」，需要边界。
 * - 助手左侧平铺、没有气泡。长回复套在气泡里会让每一屏只剩两行字，读长文时
 *   边界反而是干扰；动作（复制/分享）与时间放在文字下方一行，跟桌面端一致。
 */
@Composable
fun ChatScreen(
    chat: SessionChatController?,
    summary: SessionSummary,
    uploader: WorkspaceUploader? = null,
    reconnecting: Boolean = false,
    hostName: String = "这台电脑",
    onOpenDrawer: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    if (chat == null) {
        Column(
            Modifier
                .screenFrame()
                .background(Brush.verticalGradient(listOf(p.chatTop, p.chatMid, p.chatBottom))),
        ) {
            ChatHeader(
                title = summary.displayTitle,
                subtitle = "连接已断开",
                dotColor = p.textTertiary,
                running = false,
                onBack = onOpenDrawer,
                onStop = {},
            )
            Text(
                "连接已断开。",
                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.l),
                style = MaterialTheme.typography.bodyMedium,
                color = p.textSecondary,
            )
        }
        return
    }

    val state by chat.state.collectAsState()
    val listState = rememberLazyListState()
    var draft by remember(summary.id) { mutableStateOf("") }
    var images by remember(summary.id) { mutableStateOf(emptyList<PromptImage>()) }
    var files by remember(summary.id) { mutableStateOf(emptyList<UploadedFile>()) }
    var attachmentError by remember(summary.id) { mutableStateOf<String?>(null) }
    var uploading by remember(summary.id) { mutableStateOf(false) }
    var uploadProgress by remember(summary.id) { mutableStateOf("") }
    var pickerOpen by remember(summary.id) { mutableStateOf(false) }
    var modelPickerOpen by remember(summary.id) { mutableStateOf(false) }
    // 非空即打开分享面板，值是从哪条回复点进来的锚点（决定「这条回复」范围）。
    var shareAnchorKey by remember(summary.id) { mutableStateOf<String?>(null) }
    val attachmentScope = rememberCoroutineScope()

    // 模型面板开着时，系统返回键先关面板：直接弹出整页会丢掉用户正在看的上下文。
    BackHandler(enabled = modelPickerOpen) {
        modelPickerOpen = false
    }

    // 本地刚发出去的图片本身就带 base64，直接解码；历史里的图只有 attachment 引用，
    // 才回落到会话附件 RPC。两条路都不碰任何 HTTP 客户端。
    val loadImage: suspend (MessageImage) -> ByteArray = { image ->
        val inline = image.data
        if (inline != null) decodeImageData(inline) else chat.loadImage(image)
    }

    // 进入会话时确保打开的是这一条（从列表点进来时控制器里已经是它）。
    // 键里带上 chat：自动重连会换一个新的控制器，那时必须重新打开同一条会话。
    LaunchedEffect(summary.id, chat) {
        if (state.openSession?.id != summary.id) chat.open(summary)
    }

    val status = analysisStatus(state)
    val headerCount = if (state.hasMore) 1 else 0
    val total = headerCount + state.rows.size + if (status != null) 1 else 0
    val streamingLength = (state.rows.lastOrNull() as? AssistantRow)?.text?.length ?: 0

    // 每条回复都标出模型会把界面刷成一片重复文字。只在「这条和上一条不是同一个模型」
    // 时显示——会话里换了模型，从换的那一条开始才会有名字，一眼能看出分界。
    val modelChanges = remember(state.rows) {
        val flags = HashMap<String, String>()
        var lastRoute: String? = null
        for (row in state.rows) {
            if (row !is AssistantRow) continue
            val route = row.model?.let { "${row.provider}/${row.model}" }
            if (route != null && route != lastRoute) {
                flags[row.key] = row.model
                lastRoute = route
            }
        }
        flags
    }

    // 自己刚发出去的消息必须滚到底。这里不能靠「索引是否接近末尾」判断：键盘弹起会把
    // 列表视口压窄，末尾几项永远不可见，判断必然失败——一旦有一次没跟上，之后再也
    // 不会跟，用户就会发出消息却看不到它。
    LaunchedEffect(state.localSendSeq) {
        if (state.localSendSeq == 0) return@LaunchedEffect
        // 等一帧：这一行这时才真正进入布局，否则滚动目标还不存在。
        withFrameNanos { }
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) listState.scrollToItem(lastIndex)
    }

    // 远方推来的增量（流式回复、桌面端发来的消息）只在用户本来就停在底部时跟随，
    // 他正在往上翻历史时不要把他拽回来。刚打开会话的第一屏例外：那时必须停在最新
    // 消息上，而不是历史分页的某一页。
    var pendingInitialJump by remember(summary.id, chat) { mutableStateOf(true) }
    LaunchedEffect(total, streamingLength) {
        if (total == 0) return@LaunchedEffect
        if (!pendingInitialJump) {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
            val nearBottom = last.index >= info.totalItemsCount - 2 ||
                last.offset + last.size >= info.viewportEndOffset - 120
            if (!nearBottom) return@LaunchedEffect
        }

        // 必须是瞬时跳转：流式输出每个 token 都会改变 streamingLength，LaunchedEffect
        // 随之重启并取消上一次的滚动动画，动画永远跑不完。
        withFrameNanos { }
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) {
            listState.scrollToItem(lastIndex)
            pendingInitialJump = false
        }
    }

    Box(
        Modifier
            .screenFrame()
            .background(Brush.verticalGradient(listOf(p.chatTop, p.chatMid, p.chatBottom))),
    ) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(
                title = state.title ?: summary.displayTitle,
                // 副标题优先说「在哪台电脑上」，同步异常时才改说状态：正常时用户
                // 更想知道这条会话落在哪，出问题时才需要知道为什么不动了。
                subtitle = when {
                    reconnecting -> "正在重新连接…"
                    state.sync == SyncPhase.Live -> "$hostName · 远程"
                    else -> syncLabel(state.sync)
                },
                dotColor = when {
                    reconnecting -> p.warning
                    state.sync == SyncPhase.Live -> p.success
                    else -> p.textTertiary
                },
                running = state.running,
                onBack = onOpenDrawer,
                onStop = chat::cancelTurn,
                menu = true,
                modelCatalog = state.modelCatalog,
                modelSelection = state.modelSelection,
                modelLoading = state.modelCatalogLoading,
                onModelClick = { modelPickerOpen = true },
            )

            val fatal = when (val sync = state.sync) {
                is SyncPhase.Failed -> sync.message
                is SyncPhase.Closed -> sync.message
                else -> null
            }
            val canRetrySync = (state.sync as? SyncPhase.Failed)?.retryable ?: (state.sync is SyncPhase.Closed)
            if (reconnecting || fatal != null || state.syncWarning != null ||
                state.sendError != null || state.modelSyncError != null
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = Space.xs),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    if (reconnecting) {
                        NoticeBar("连接中断，正在自动重连…")
                    } else if (fatal != null) {
                        InlineError(
                            fatal,
                            retryLabel = if (canRetrySync) "重新同步" else null,
                            onRetry = if (canRetrySync) chat::retry else null,
                        )
                    }
                    // 模型目录/选择投影的控制流断开时，模型胶囊会停在旧值。这里必须
                    // 明说并给重试，不能让它静默不同步。
                    state.modelSyncError?.let {
                        InlineError(it, retryLabel = "重新同步模型", onRetry = chat::retryModelSync)
                    }
                    state.syncWarning?.let { InlineError(it) }
                    state.sendError?.let { InlineError(it) }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                // 底部留出输入栏的高度：输入栏是浮在内容上的，不留白会被挡住最后一条。
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = Space.s, bottom = 104.dp),
                // 消息不足一屏时贴着底部（与主流聊天应用一致），超过一屏后按正常流排。
                verticalArrangement = Arrangement.spacedBy(Space.l, Alignment.Bottom),
            ) {
                if (state.hasMore) {
                    item {
                        LoadOlderRow(loading = state.loadingMore, onClick = chat::loadOlder)
                    }
                }

                items(state.rows) { row ->
                    when (row) {
                        is UserRow -> UserMessage(row, loadImage)
                        is AssistantRow -> AssistantMessage(
                            row = row,
                            changedModel = modelChanges[row.key],
                            loadImage = loadImage,
                            onQuote = { text -> draft = quoteIntoDraft(draft, text) },
                            onShareConversation = { shareAnchorKey = row.key },
                        )
                        is NoticeRow -> NoticeLine(row)
                        is ToolRow -> {
                            val waitingFor = when {
                                state.approvals.any { it.callId != null && it.callId == row.callId } -> "批准"
                                // legacy 提问事件不带 callId，只能用问题 id 在工具参数里认领。
                                state.questions.any { pending ->
                                    val callId = pending.callId
                                    if (callId != null) callId == row.callId
                                    else pending.questions.any { row.arguments.contains("\"${it.id}\"") }
                                } -> "回答"
                                else -> null
                            }
                            // 等用户处理的调用必须显眼（有按钮要点），其余收敛成一行——
                            // 一次工具调用占掉半屏会让对话读起来全是大块卡片。
                            if (waitingFor != null && row.phase == ToolPhase.Running) {
                                ToolCallCard(row, loadImage, waitingFor = waitingFor)
                            } else {
                                ToolSummaryLine(row, loadImage)
                            }
                        }
                    }
                }

                items(state.approvals, key = { "approval-${it.eventId}" }) { approval ->
                    ToolApprovalCard(
                        approval = approval,
                        tool = state.rows.filterIsInstance<ToolRow>().firstOrNull { it.callId == approval.callId },
                        enabled = state.approvalReady && state.sync == SyncPhase.Live && !reconnecting,
                        onRespond = chat::respondApproval,
                    )
                }
                state.approvalError?.let { message ->
                    // 重连中这条只是一次断线带来的噪声，等连上自然会重新同步审批。
                    if (!reconnecting) item { InlineError(message, retryLabel = "重新同步", onRetry = chat::retry) }
                }

                items(state.questions, key = { "question-${it.eventId}" }) { pending ->
                    QuestionCard(
                        pending = pending,
                        enabled = state.approvalReady && state.sync == SyncPhase.Live && !reconnecting,
                        onToggle = { questionId, label -> chat.toggleOption(pending.eventId, questionId, label) },
                        onDraft = { questionId, text -> chat.setQuestionDraft(pending.eventId, questionId, text) },
                        onSubmit = { chat.submitAnswers(pending.eventId) },
                        onSkip = { chat.submitAnswers(pending.eventId) },
                    )
                }

                if (status != null) {
                    item(key = "analysis-status") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(status, style = MaterialTheme.typography.bodySmall, color = p.textTertiary)
                            Spacer(Modifier.width(8.dp))
                            TypingDots(color = p.textTertiary)
                        }
                    }
                }

                // 空会话占满整个视口再居中：列表本身是底部对齐的，直接塞一个小条目
                // 会贴在输入框上方，看起来像「内容掉到底部」。
                if (state.rows.isEmpty() && status == null) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            if (state.sync == SyncPhase.Live) {
                                EmptyConversation()
                            } else {
                                Text(
                                    "正在同步…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = p.textTertiary,
                                )
                            }
                        }
                    }
                }
                // 尾部锚点：最后一项可能是一张比视口还高的长截图，滚到它的顶部会让下半截
                // 永远露不出来；多放一个 1dp 占位，滚到它就等于精确贴底。
                item(key = "tail") { Spacer(Modifier.height(1.dp)) }
            }
        }

        // ------------------------------------------------------------- 输入区
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 内容从输入栏下面穿过去时会被压住看不清，用一层同色渐变把它糊掉。
                .background(Brush.verticalGradient(listOf(Color.Transparent, p.chatBottom)))
                .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 10.dp),
        ) {
            if (images.isNotEmpty() || files.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = Space.xs),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    images.forEachIndexed { index, image ->
                        AttachmentChip("图片 · ${image.name}") {
                            images = images.filterIndexed { i, _ -> i != index }
                        }
                    }
                    files.forEachIndexed { index, file ->
                        AttachmentChip("文件 · ${file.name}") {
                            files = files.filterIndexed { i, _ -> i != index }
                        }
                    }
                }
            }
            attachmentError?.let {
                Text(it, color = p.danger, style = MaterialTheme.typography.bodySmall)
            }
            if (uploading) {
                Text(uploadProgress, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (pickerOpen) {
                AttachmentPicker(
                    enabled = state.sync == SyncPhase.Live && !reconnecting && !uploading && !state.sending,
                    fileUploadEnabled = uploader?.supported == true,
                    modifier = Modifier.padding(bottom = Space.xs),
                    onImage = { image ->
                        if (images.size >= 4 || images.sumOf { it.bytes.toLong() } + image.bytes > 2 * 1024 * 1024) {
                            attachmentError = "最多 4 张图片，合计不超过 2 MiB"
                        } else {
                            images = images + image
                            attachmentError = null
                        }
                        pickerOpen = false
                    },
                    onFile = { file ->
                        val currentUploader = uploader
                        if (currentUploader != null && !uploading) {
                            uploading = true
                            attachmentError = null
                            uploadProgress = "正在上传 ${file.name}…"
                            pickerOpen = false
                            attachmentScope.launch {
                                try {
                                    val uploaded = currentUploader.upload(summary.id, file) { done, totalBytes ->
                                        uploadProgress = "正在上传 ${file.name} · ${if (totalBytes == 0) 100 else done.toLong() * 100 / totalBytes}%"
                                    }
                                    files = files + uploaded
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    attachmentError = failure.message ?: "上传失败，请重试"
                                } finally {
                                    uploading = false
                                }
                            }
                        }
                    },
                )
            }
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = state.sending || uploading,
                enabled = state.sync == SyncPhase.Live && !reconnecting,
                hasAttachments = images.isNotEmpty() || files.isNotEmpty(),
                pickerOpen = pickerOpen,
                onTogglePicker = { pickerOpen = !pickerOpen },
                onSend = {
                    if (chat.send(uploadedFilesPrompt(draft, files), images)) {
                        draft = ""
                        images = emptyList()
                        files = emptyList()
                        attachmentError = null
                    }
                },
            )
        }

        // 抽屉盖在页面最上层；切换后栈顶被替换，旧会话不会留在返回栈里。
        if (modelPickerOpen) {
            ModelPicker(
                state = state,
                onClose = { modelPickerOpen = false },
                onRetry = chat::loadModelCatalog,
                onSelect = { selection -> chat.selectModel(selection) },
            )
        }

        // 分享面板是独立窗口，放这里只是为了共用同一份会话状态。
        shareAnchorKey?.let { anchor ->
            ShareSheet(
                rows = state.rows,
                title = state.title ?: summary.displayTitle,
                hostName = hostName,
                modelName = state.modelSelection?.model ?: state.modelCatalog?.default?.model,
                anchorKey = anchor,
                onDismiss = { shareAnchorKey = null },
            )
        }
    }
}

/* ------------------------------------------------------------------ 模型选择 */

/**
 * 模型选择面板。
 *
 * 数据全部来自 Host 的 `session/modelCatalog`：分组、名字、描述、徽标、推理强度都
 * 照原样展示，不做本地拼装。切换走 `session/selectModel`，成功后以 Host 返回的
 * `selected` 为准；在此之前保持原值，避免出现「点了就变了但其实没成功」的假象。
 */
@Composable
private fun ModelPicker(
    state: ChatState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (ModelSelection) -> Unit,
) {
    val p = LocalRuyiPalette.current
    val catalog = state.modelCatalog
    val current = state.modelSelection ?: catalog?.default
    // `routableProviders` 在契约里是必填数组：空数组表示「零个 provider 可路由」，
    // 必须禁用全部模型。只有字段整个缺席（旧版 Host）时才按「未知」放行，避免少
    // 一个字段就把整份目录误判成不可用。区分逻辑收在 [ModelCatalog.isRoutable]。

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = if (p.dark) 0.55f else 0.28f))
                .clickable(onClick = onClose)
                .semantics { contentDescription = "关闭模型选择"; role = Role.Button },
        )
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .floatShadow(p, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(p.float)
                .padding(top = 10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp)).background(p.hairline))
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("选择模型", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, color = p.text)
                    Text(
                        current?.let { selection ->
                            catalog?.find(selection.provider, selection.model)?.label
                                ?: readableModelId(selection.model)
                        } ?: "尚未确定",
                        style = MaterialTheme.typography.labelSmall, color = p.textTertiary,
                    )
                }
                if (state.modelSwitching) TypingDots(color = p.textTertiary)
            }

            state.modelCatalogError?.let {
                Box(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                    InlineError(it, retryLabel = "重新读取", onRetry = onRetry)
                }
            }
            state.modelSwitchError?.let {
                Box(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { InlineError(it) }
            }

            when {
                catalog == null && state.modelCatalogLoading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) { TypingDots(color = p.textTertiary) }

                catalog == null -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("这台电脑没有返回模型列表", style = MaterialTheme.typography.bodyMedium, color = p.textTertiary)
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp),
                ) {
                    catalog.groups.forEach { group ->
                        item(key = "g-${group.id}") {
                            Text(
                                group.label,
                                Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = p.textTertiary,
                            )
                        }
                        items(group.models, key = { "${group.id}/${it.id}" }) { model ->
                            val active = current?.provider == group.id && current.model == model.id
                            val routable = catalog.isRoutable(group.id)
                            ModelRow(
                                name = model.label,
                                description = model.description,
                                badge = model.badge,
                                active = active,
                                enabled = !state.modelSwitching && routable,
                                disabledReason = if (routable) null else "当前不可路由：桌面端的模型提供方没有可用连接",
                                onClick = {
                                    if (!active) {
                                        onSelect(
                                            ModelSelection(
                                                provider = group.id,
                                                model = model.id,
                                                reasoningEffort = model.defaultEffort,
                                            ),
                                        )
                                    }
                                },
                            )
                            if (active && model.efforts.isNotEmpty()) {
                                EffortRow(
                                    efforts = model.efforts,
                                    selected = current?.reasoningEffort ?: model.defaultEffort,
                                    enabled = !state.modelSwitching && routable,
                                    onSelect = { effort ->
                                        onSelect(ModelSelection(group.id, model.id, effort))
                                    },
                                )
                            }
                        }
                    }
                    if (catalog.failures.isNotEmpty()) {
                        item(key = "failures") {
                            Text(
                                "读取失败的提供方",
                                Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = p.textTertiary,
                            )
                        }
                        items(catalog.failures, key = { "f-${it.id}" }) { failure ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                                Text(failure.name, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                                Text(failure.message, style = MaterialTheme.typography.labelSmall, color = p.danger)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    name: String,
    description: String?,
    badge: String?,
    active: Boolean,
    enabled: Boolean,
    disabledReason: String? = null,
    onClick: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) p.accentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = name; role = Role.Button }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        active -> p.onAccentSoft
                        enabled -> p.text
                        else -> p.textTertiary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                badge?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        it,
                        Modifier.clip(RoundedCornerShape(6.dp)).background(p.chip)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = p.textSecondary,
                    )
                }
            }
            description?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = p.textTertiary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            // 禁用必须带原因：只把行变灰，用户会以为是加载中或 App 卡住了。
            disabledReason?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = p.warning, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (active) {
            Spacer(Modifier.width(8.dp))
            GlyphCheckCircle(size = 18.dp, tint = p.accent)
        }
    }
}

/** 推理强度：同一模型内的档位切换，仍然走 selectModel；下面一行解释当前档位。 */
@Composable
private fun EffortRow(
    efforts: List<ModelEffort>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val p = LocalRuyiPalette.current
    val activeEffort = efforts.firstOrNull { it.id == selected } ?: efforts.firstOrNull()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            efforts.forEach { effort ->
                val active = effort.id == activeEffort?.id
                Text(
                    effort.name,
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (active) p.accent else p.chip)
                        .clickable(enabled = enabled) { onSelect(effort.id) }
                        .semantics { contentDescription = "推理强度 ${effort.name}"; role = Role.Button }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) p.onAccent else p.textSecondary,
                )
            }
        }
        // Host 给了 description 就展示：只写「高 / 低」时用户不知道代价是什么。
        activeEffort?.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
            )
        }
    }
}

/* ------------------------------------------------------------------ 顶栏 */

/**
 * 悬浮顶栏：左圆钮、中间标题胶囊、右侧（运行中才有）停止钮。
 *
 * 不做成实心横条是有意的——实心横条会把页面切成「头/身」两截，而这里更希望
 * 内容看起来是在同一张纸上滚动。副标题左边的小圆点承担在线状态，比纯文字
 * 「实时同步中」更容易被一眼扫到。
 */
@Composable
private fun ChatHeader(
    title: String,
    subtitle: String,
    dotColor: Color,
    running: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    menu: Boolean = false,
    modelCatalog: ModelCatalog? = null,
    modelSelection: ModelSelection? = null,
    modelLoading: Boolean = false,
    onModelClick: () -> Unit = {},
) {
    val p = LocalRuyiPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FloatCircle(size = 44.dp, onClick = onBack, label = if (menu) "打开会话抽屉" else "返回") {
            if (menu) GlyphMenu(size = 20.dp, tint = p.text)
            else GlyphBack(size = 19.dp, tint = p.text)
        }
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier
                .weight(1f)
                .floatShadow(p, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(p.float)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text(
                title,
                // 标题加一档字重：胶囊里只有两行小字，没有分割线，全靠字重分出主次。
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = p.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = p.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (running) {
            Spacer(Modifier.width(8.dp))
            FloatCircle(size = 44.dp, onClick = onStop, label = "中止本轮") {
                GlyphStop(size = 20.dp, tint = p.danger)
            }
        } else {
            Spacer(Modifier.width(8.dp))
            ModelPill(
                catalog = modelCatalog,
                selection = modelSelection,
                loading = modelLoading,
                onClick = onModelClick,
            )
        }
    }
}

/**
 * 顶栏右侧的模型胶囊。
 *
 * 只显示 Host 真的给出的名字：目录里查不到时退回 model id，连 id 都没有才显示
 * 「选择模型」。不做「Gpt 6.1」这类占位文案——用户会以为那是当前模型。
 */
@Composable
private fun ModelPill(
    catalog: ModelCatalog?,
    selection: ModelSelection?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    // 与模型弹窗一致：未显式选择时回退到 Host 下发的默认模型，避免右上角
    // 在新对话里只显示「选择模型」却看不出当前生效的是哪个。
    val effective = selection ?: catalog?.default
    val model = effective?.let { catalog?.find(it.provider, it.model) }
    val label = model?.label
        ?: effective?.model?.let(::readableModelId)
        ?: if (loading) "读取模型…" else "选择模型"
    Row(
        Modifier
            .height(44.dp)
            .floatShadow(p, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(p.float)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "切换模型，当前 $label"; role = Role.Button }
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphSpark(size = 15.dp, tint = p.accent)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = p.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Spacer(Modifier.width(2.dp))
        Box(Modifier.rotate(90f)) { GlyphChevron(size = 14.dp, tint = p.textTertiary) }
    }
}

/** 浮在纸上的白色圆钮。阴影必须落在裁剪之前，否则会被自己裁掉。 */
@Composable
private fun FloatCircle(
    size: Dp,
    onClick: () -> Unit,
    label: String,
    content: @Composable () -> Unit,
) {
    val p = LocalRuyiPalette.current
    Box(
        Modifier
            .size(size)
            .floatShadow(p, CircleShape)
            .clip(CircleShape)
            .background(p.float)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 统一的悬浮阴影：浅色下是极淡的蓝灰，深色下才需要实一点的黑。 */
private fun Modifier.floatShadow(p: RuyiPalette, shape: Shape) =
    shadow(
        elevation = 10.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = if (p.dark) 0.5f else 0.06f),
        spotColor = Color.Black.copy(alpha = if (p.dark) 0.5f else 0.10f),
    )

/* ------------------------------------------------------------------ 消息 */

@Composable
private fun UserMessage(row: UserRow, loadImage: suspend (MessageImage) -> ByteArray) {
    val p = LocalRuyiPalette.current
    val imageShape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 44.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        row.images.forEach { image ->
            AttachmentImagePreview(
                image = image,
                modifier = Modifier
                    .widthIn(max = 232.dp)
                    .clip(imageShape)
                    .border(1.dp, p.hairline, imageShape),
                load = loadImage,
            )
        }
        if (row.text.isNotBlank()) {
            SelectionContainer {
                Text(
                    row.text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(p.bubbleUserSoft)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.onBubbleUserSoft,
                )
            }
        }
        when (row.phase) {
            PromptPhase.Sending -> Text(
                "发送中…",
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
            )

            PromptPhase.Failed -> Text(
                row.error ?: "发送失败",
                style = MaterialTheme.typography.labelSmall,
                color = p.danger,
                textAlign = TextAlign.End,
            )

            PromptPhase.Sent -> Unit
        }
    }
}

/**
 * 助手消息：不套气泡，直接排在纸上。
 *
 * 回复经常是几百字的清单或代码，装进气泡后每一行都要为圆角让位，一屏能读到的
 * 内容比平铺少三成。动作行（复制/分享/时间）放在正文下方、靠右给时间，和桌面端
 * 的位置一致——用户在两处看到的是同一套阅读顺序。
 */
@Composable
private fun AssistantMessage(
    row: AssistantRow,
    changedModel: String?,
    loadImage: suspend (MessageImage) -> ByteArray,
    onQuote: (String) -> Unit,
    onShareConversation: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    val imageShape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        row.images.forEach { image ->
            AttachmentImagePreview(
                image = image,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .clip(imageShape)
                    .border(1.dp, p.hairline, imageShape),
                load = loadImage,
            )
        }
        // 思考过程和最终回答是两段不同的内容：前者只能来自 Host 实际下发的 reasoning，
        // 后者才是答案。分区渲染，避免把推理误当成回复。
        if (row.reasoning.isNotBlank()) {
            ReasoningSection(
                text = row.reasoning,
                active = row.streaming && row.reasoningActive,
                rowKey = row.key,
            )
        }
        if (row.text.isBlank() && row.streaming && row.reasoning.isBlank()) {
            Box(Modifier.padding(vertical = 8.dp)) { TypingDots(color = p.textTertiary) }
        } else if (row.text.isNotBlank()) {
            MarkdownContent(text = row.text, modifier = Modifier.fillMaxWidth())
        }
        if (row.interrupted) {
            Text("输出被中断", style = MaterialTheme.typography.labelSmall, color = p.danger)
        }
        // 流式过程中给动作没有意义（复制到一半的答案），完成后才出现。
        if (!row.streaming && row.text.isNotBlank()) {
            MessageActions(
                row = row,
                changedModel = changedModel,
                onQuote = onQuote,
                onShareConversation = onShareConversation,
            )
        }
    }
}

/**
 * 可折叠的思考过程：只显示 Host 真实下发的 reasoning，不生成、不补全、不落盘。
 *
 * 未手动切换时，流式推理阶段自动展开让人看到真实过程，进入正文或完成后自动折叠；
 * 用户点过就以用户选择为准，按 rowKey 记忆，重组与滚动都不会丢状态。
 * 折叠态不渲染整段正文（只留一行预览），长思考配高频增量不会每帧重排上万字。
 */
@Composable
private fun ReasoningSection(
    text: String,
    active: Boolean,
    rowKey: String,
) {
    val p = LocalRuyiPalette.current
    var manualExpanded by remember(rowKey) { mutableStateOf<Boolean?>(null) }
    val expanded = manualExpanded ?: active
    val preview = remember(text) { text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.chip)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起思考过程" else "展开思考过程",
                ) { manualExpanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphSpark(size = 14.dp, tint = p.textTertiary)
            Spacer(Modifier.width(7.dp))
            Text(
                if (active) "思考过程 · 分析中" else "思考过程",
                style = MaterialTheme.typography.labelMedium,
                color = p.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.rotate(if (expanded) 270f else 90f)) {
                GlyphChevron(size = 13.dp, tint = p.textTertiary)
            }
        }
        if (expanded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
            }
        } else if (preview.isNotBlank()) {
            Text(
                preview,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = p.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 一条消息的真实元信息，用 `·` 串成一行。
 *
 * 每一段都只在 Host 真的给了值时才出现：没有时长就不写「0 秒」，没有用量就不写
 * 「0 tokens」。分段顺序按「用户最想知道什么」排：谁答的（仅换模型时）→ 这步
 * 花了多久 → 首字等了多久（短到无感就不提）→ 用了多少 token → 推理 token →
 * 命中多少缓存（读/写分开；只有读缓存时仍写「缓存」，不改变旧文案）。
 */
private fun assistantMeta(row: AssistantRow, changedModel: String?): String {
    val parts = ArrayList<String>(6)
    changedModel?.let { parts += readableModelId(it) }
    formatElapsed(row.stepDurationMs).takeIf { it.isNotEmpty() }?.let { parts += "模型 $it" }
    row.ttftMs?.takeIf { it >= 300 }?.let { ttft ->
        formatElapsed(ttft).takeIf { it.isNotEmpty() }?.let { parts += "首字 $it" }
    }
    row.usage?.let { usage: TokenUsage ->
        parts += "${formatTokenCount(usage.effectiveTotal)} tokens"
        usage.reasoningTokens?.takeIf { it > 0 }?.let { parts += "推理 ${formatTokenCount(it)}" }
        val read = usage.cacheReadTokens?.takeIf { it > 0 }
        val write = usage.cacheWriteTokens?.takeIf { it > 0 }
        when {
            read != null && write != null -> parts += "缓存读 ${formatTokenCount(read)} / 写 ${formatTokenCount(write)}"
            read != null -> parts += "缓存 ${formatTokenCount(read)}"
            write != null -> parts += "缓存写 ${formatTokenCount(write)}"
        }
    }
    return parts.joinToString(" · ")
}

/**
 * 一条消息的动作行：复制/分享是快捷按钮，「更多」里是同一批动作加引用、思考过程复制，
 * 以及进入统一分享面板（可选这条回复 / 选中消息 / 整段对话）。
 *
 * 这里不放「重新回答 / 分叉」：DSH 0.2.1 的会话日志是 append-only，没有原地 regenerate，
 * 参考端靠 `session/fork` 在事件 seq 前缀切分实现；Native 目前既没有该 RPC，行模型也没有
 * 能映射到 `atSeq` 的字段。放假按钮等于假装做了做不到的事。
 */
@Composable
private fun MessageActions(
    row: AssistantRow,
    changedModel: String?,
    onQuote: (String) -> Unit,
    onShareConversation: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    val text = row.text
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val meta = remember(row, changedModel) { assistantMeta(row, changedModel) }
    var menuOpen by remember(row.key) { mutableStateOf(false) }
    val copyReply = { clipboard.setText(AnnotatedString(text)) }
    val shareReply = {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "分享这段回复"))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlyphAction(label = "复制", onClick = copyReply) {
            GlyphCopy(size = 16.dp, tint = p.textTertiary)
        }
        GlyphAction(label = "分享", onClick = shareReply) {
            GlyphShare(size = 16.dp, tint = p.textTertiary)
        }
        Box {
            GlyphAction(label = "更多操作", onClick = { menuOpen = true }) {
                GlyphMore(size = 16.dp, tint = p.textTertiary)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("引用到输入框", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { menuOpen = false; onQuote(text) },
                )
                DropdownMenuItem(
                    text = { Text("复制回复", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { menuOpen = false; copyReply() },
                )
                DropdownMenuItem(
                    text = { Text("分享回复", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { menuOpen = false; shareReply() },
                )
                DropdownMenuItem(
                    text = { Text("分享对话…", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { menuOpen = false; onShareConversation() },
                )
                // 思考过程只在 Host 真的下发过时才给复制入口。
                if (row.reasoning.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text("复制思考过程", style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(row.reasoning))
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(2.dp))
        if (meta.isNotEmpty()) {
            Text(
                meta,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatWhen(row.time),
            style = MaterialTheme.typography.labelSmall,
            color = p.textTertiary,
        )
    }
}

@Composable
private fun GlyphAction(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun NoticeLine(row: NoticeRow) {
    val p = LocalRuyiPalette.current
    Text(
        row.text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xl),
        style = MaterialTheme.typography.labelSmall,
        color = if (row.isError) p.danger else p.textTertiary,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun EmptyConversation() {
    val p = LocalRuyiPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text("新会话", style = MaterialTheme.typography.titleMedium, color = p.text)
        Text(
            "这个会话还没有消息。直接说你想让它做什么。",
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadOlderRow(loading: Boolean, onClick: () -> Unit) {
    val p = LocalRuyiPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(p.float.copy(alpha = 0.75f))
            .clickable(enabled = !loading, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (loading) "正在加载…" else "加载更早的消息",
            style = MaterialTheme.typography.labelSmall,
            color = p.textSecondary,
        )
    }
}

/* ------------------------------------------------------------------ 输入栏 */

/**
 * 悬浮输入栏：一个白色胶囊，左边「+」展开附件，右边发送。
 *
 * 「+」是有必要的：之前两个「添加图片 / 上传文件」按钮常驻在输入框上方，没打算
 * 发附件的人也要一直看着它们。收进一个加号后，常态只剩输入框本身。
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    enabled: Boolean = true,
    hasAttachments: Boolean = false,
    pickerOpen: Boolean = false,
    onTogglePicker: () -> Unit = {},
    onSend: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    val canSend = (draft.isNotBlank() || hasAttachments) && !sending && enabled
    // 56dp 高的胶囊、48dp 的圆钮：手要够得到。之前 38dp 的钮在拇指滑动时经常点空。
    val shape = RoundedCornerShape(28.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .floatShadow(p, shape)
            .clip(shape)
            .background(p.float)
            .padding(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (pickerOpen) p.accentSoft else p.chipNeutral)
                .clickable(enabled = enabled, onClick = onTogglePicker)
                .semantics { contentDescription = if (pickerOpen) "收起附件" else "添加附件"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (pickerOpen) p.onAccentSoft else p.textSecondary
            Box(Modifier.rotate(if (pickerOpen) 45f else 0f)) { GlyphPlus(size = 20.dp, tint = tint) }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp, max = 132.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.isEmpty()) {
                Text(
                    if (enabled) "给如意发消息…" else "已断开连接，暂时无法发送",
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.textTertiary,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text),
                maxLines = 5,
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (canSend) p.accent else p.chipNeutral)
                .clickable(enabled = canSend, onClick = onSend)
                .semantics { contentDescription = "发送"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            if (sending) {
                TypingDots(color = p.textTertiary)
            } else {
                GlyphSend(size = 20.dp, tint = if (canSend) p.onAccent else p.textTertiary)
            }
        }
    }
}

/** 待发送附件的胶囊标签。用「类型 · 名字」+ 独立可点的 ×，避免整块可点导致误删。 */
@Composable
private fun AttachmentChip(label: String, onRemove: () -> Unit) {
    val p = LocalRuyiPalette.current
    Row(
        modifier = Modifier
            .floatShadow(p, RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .background(p.float)
            .padding(start = 12.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.widthIn(max = 168.dp),
            style = MaterialTheme.typography.labelSmall,
            color = p.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "移除 $label"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Text("×", style = MaterialTheme.typography.labelLarge, color = p.textTertiary)
        }
    }
}

/* ------------------------------------------------------------------ 文案 */

private fun syncLabel(phase: SyncPhase): String = when (phase) {
    SyncPhase.Idle -> "未同步"
    SyncPhase.Connecting -> "正在同步…"
    SyncPhase.Live -> "实时同步中"
    SyncPhase.Reconnecting -> "正在重新同步…"
    is SyncPhase.Closed -> "已断开"
    is SyncPhase.Failed -> if (phase.retryable) "同步失败" else "连接已断开"
}
