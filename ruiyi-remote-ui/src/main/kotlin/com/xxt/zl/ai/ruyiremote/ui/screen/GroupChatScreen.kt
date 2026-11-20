package com.xxt.zl.ai.ruyiremote.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.session.GroupTimelineController
import com.xxt.zl.ai.ruyiremote.session.MessageImage
import com.xxt.zl.ai.ruyiremote.session.SessionSummary
import com.xxt.zl.ai.ruyiremote.session.TimelineEntry
import com.xxt.zl.ai.ruyiremote.ui.components.AttachmentImagePreview
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphBack
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphChevron
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphRefresh
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphSend
import com.xxt.zl.ai.ruyiremote.ui.components.InlineError
import com.xxt.zl.ai.ruyiremote.ui.components.MarkdownContent
import com.xxt.zl.ai.ruyiremote.ui.components.TypingDots
import com.xxt.zl.ai.ruyiremote.ui.formatWhen
import com.xxt.zl.ai.ruyiremote.ui.screenFrame
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.RuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Space

/**
 * T16 群聊页：把同一小通 Bot 群各成员会话的消息，按时间合并成一条讨论流。
 *
 * 这是 A 路线的本地合并视图——不是真正的群房间：每条消息都来自手机已经拿得到的
 * 成员会话快照，不接 DS Bot、不改中继。页头明确写「本地合并」，避免用户误以为是
 * 桌面端那种有群公告/成员详情的实时群。发言人和「发给谁」都靠 title 解析出的成员名。
 */
@Composable
fun GroupChatScreen(
    controller: GroupTimelineController?,
    group: String,
    members: List<SessionSummary>,
    hostName: String = "这台电脑",
    onBack: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    if (controller == null) {
        Column(
            Modifier
                .screenFrame()
                .background(Brush.verticalGradient(listOf(p.chatTop, p.chatMid, p.chatBottom))),
        ) {
            GroupChatHeader(group, 0, p, onBack, onRefresh = {})
            Text(
                "连接已断开，读不到这个群的消息。",
                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.l),
                style = MaterialTheme.typography.bodyMedium,
                color = p.textSecondary,
            )
        }
        return
    }

    val state by controller.state.collectAsState()
    var draft by remember(group) { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 打开页 / 重连换控制器后打开这一群。members 来自导航（抽屉点进来时这一群的成员
    // 会话快照），控制器据此解析成员名；键里带 controller 以覆盖重连换控制器的情况。
    LaunchedEffect(group, controller) { controller.open(group, members) }

    // 发送成功后清一次草稿。failed 不清，让用户能直接改了重发。
    LaunchedEffect(state.sentMarker) {
        if (state.sentMarker > 0L) draft = ""
    }

    // 新成员发言合并进来时跟随到底部（用户正在往上翻历史时不拽回）。
    LaunchedEffect(state.entries.size) {
        if (state.entries.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        if (last == null || last.index >= info.totalItemsCount - 2) {
            withFrameNanos { }
            listState.scrollToItem(info.totalItemsCount - 1)
        }
    }

    Box(
        Modifier
            .screenFrame()
            .background(Brush.verticalGradient(listOf(p.chatTop, p.chatMid, p.chatBottom))),
    ) {
        Column(Modifier.fillMaxSize()) {
            GroupChatHeader(group, state.members.size, p, onBack, onRefresh = controller::refresh)

            // 本地合并视图的边界说明：一次说清，避免把「各成员会话拼起来」误当成真群聊。
            Text(
                "本地合并视图 · ${hostName} 上 ${state.members.size} 位成员的会话按时间拼成一条讨论，非实时群房间。",
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
            )

            if (state.error != null) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = Space.xs)) {
                    InlineError(state.error!!, retryLabel = "重新读取", onRetry = controller::refresh)
                }
            }

            // 「发给谁」：下拉选择。默认「全体成员」（广播），也可指定某位成员。
            if (state.members.isNotEmpty()) {
                TargetPicker(
                    members = state.members.map { it.sessionId to it.name },
                    selected = state.targetSessionId,
                    onSelect = controller::setTarget,
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = Space.s, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Space.l, Alignment.Bottom),
            ) {
                if (state.loading && state.entries.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            TypingDots(color = p.textTertiary)
                        }
                    }
                } else if (state.entries.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "这个群还没有消息。在下方选一位成员，发一句试试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = p.textTertiary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    items(state.entries, key = { it.id }) { entry ->
                        TimelineBubble(entry, group, controller)
                    }
                }
                item(key = "tail") { Spacer(Modifier.height(1.dp)) }
            }
        }

        // 输入区浮在内容上。
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, p.chatBottom)))
                .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 10.dp),
        ) {
            state.sendError?.let {
                Text(it, color = p.danger, style = MaterialTheme.typography.bodySmall)
            }
            val targetName = if (state.targetSessionId == null) {
                "全体成员"
            } else {
                state.members.firstOrNull { it.sessionId == state.targetSessionId }?.name
            }
            GroupComposer(
                draft = draft,
                onDraftChange = { draft = it },
                sending = state.sending,
                enabled = state.members.isNotEmpty() && !state.loading,
                targetName = targetName,
                onSend = { controller.send(draft) },
            )
        }
    }
}

@Composable
private fun GroupChatHeader(
    group: String,
    memberCount: Int,
    p: RuyiPalette,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).floatShadow(p, CircleShape).clip(CircleShape).background(p.float)
                .clickable(onClick = onBack)
                .semantics { contentDescription = "返回会话列表"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { GlyphBack(size = 19.dp, tint = p.text) }
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier.weight(1f).floatShadow(p, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp)).background(p.float)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text(
                group,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = p.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("$memberCount 位成员 · 本地合并", style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(44.dp).floatShadow(p, CircleShape).clip(CircleShape).background(p.float)
                .clickable(onClick = onRefresh)
                .semantics { contentDescription = "刷新群消息"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { GlyphRefresh(size = 18.dp, tint = p.textSecondary) }
    }
}

/** 发给谁：下拉选择。默认「全体成员」广播，也可指定某位成员。 */
@Composable
private fun TargetPicker(
    members: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val p = LocalRuyiPalette.current
    var expanded by remember { mutableStateOf(false) }
    val selectedName = selected?.let { id -> members.firstOrNull { it.first == id }?.second } ?: "全体成员"
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
        Text("发给谁", style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .floatShadow(p, shape)
                    .clip(shape)
                    .background(p.float)
                    .clickable { expanded = true }
                    .semantics {
                        contentDescription = "发给 $selectedName，点击选择成员"
                        role = Role.Button
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.text,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.rotate(90f)) { GlyphChevron(size = 14.dp, tint = p.textSecondary) }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(p.float),
            ) {
                // 全体成员置顶，选中时高亮；点它广播给所有成员。
                DropdownMenuItem(
                    text = {
                        Text(
                            "全体成员",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected == null) p.accent else p.text,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(null)
                    },
                )
                members.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (id == selected) p.accent else p.text,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(id)
                        },
                    )
                }
            }
        }
    }
}

/** 一条合并消息：用户广播右对齐蓝气泡并标「我」；成员发言左对齐，带发言人名。 */
@Composable
private fun TimelineBubble(
    entry: TimelineEntry,
    group: String,
    controller: GroupTimelineController,
) {
    val p = LocalRuyiPalette.current
    val isMine = entry.kind == TimelineEntry.TimelineKind.USER
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        Text(
            if (isMine) "我" else (entry.speaker ?: group),
            style = MaterialTheme.typography.labelSmall,
            color = if (isMine) p.accent else p.textTertiary,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
        )
        Box(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isMine) p.bubbleUserSoft else p.chip)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // 广播条目的 sessionId 为 null，但图片是在某个成员会话里收到的；
            // imageSourceSessionId 记录了实际来源，用它读协议图与工作区图。
            val imageSource = entry.imageSourceSessionId ?: entry.sessionId
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 协议图片块（type=="image"）：对齐普通聊天页 AttachmentImagePreview。
                entry.images.forEach { image ->
                    AttachmentImagePreview(
                        image = image,
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, p.hairline, RoundedCornerShape(12.dp)),
                        load = { img ->
                            if (imageSource != null) controller.loadSessionImage(imageSource, img)
                            else throw IllegalStateException("群消息缺少图片来源会话")
                        },
                    )
                }
                // Markdown 正文（标题/列表/表格/代码块/```svg/<svg>、工作区相对路径图）。
                if (entry.text.isNotBlank()) {
                    if (imageSource != null) {
                        MarkdownContent(
                            entry.text,
                            onLoadImage = { path -> controller.loadWorkspaceImage(imageSource, path) },
                        )
                    } else {
                        MarkdownContent(entry.text)
                    }
                }
            }
        }
        Text(
            formatWhen(entry.time),
            style = MaterialTheme.typography.labelSmall,
            color = p.textTertiary,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp),
        )
    }
}

@Composable
private fun GroupComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    enabled: Boolean,
    targetName: String?,
    onSend: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    val canSend = draft.isNotBlank() && enabled && !sending
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = Modifier.fillMaxWidth().floatShadow(p, shape).clip(shape).background(p.float).padding(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 132.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.isEmpty()) {
                Text(
                    if (enabled) "发给 ${targetName ?: "成员"}…" else "没有可发送的成员",
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
            )
        }
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape)
                .background(if (canSend) p.accent else p.chipNeutral)
                .clickable(enabled = canSend, onClick = onSend)
                .semantics { contentDescription = "发送"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            if (sending) TypingDots(color = p.textTertiary)
            else GlyphSend(size = 20.dp, tint = if (canSend) p.onAccent else p.textTertiary)
        }
    }
}

/** 浮在纸上的白色圆钮；阴影在裁剪之前画，否则会被裁掉。 */
private fun Modifier.floatShadow(p: RuyiPalette, shape: Shape) =
    shadow(
        elevation = 10.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = if (p.dark) 0.5f else 0.06f),
        spotColor = Color.Black.copy(alpha = if (p.dark) 0.5f else 0.10f),
    )
