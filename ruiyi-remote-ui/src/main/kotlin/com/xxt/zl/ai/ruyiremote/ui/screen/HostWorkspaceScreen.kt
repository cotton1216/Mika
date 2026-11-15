package com.xxt.zl.ai.ruyiremote.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.session.ChatState
import com.xxt.zl.ai.ruyiremote.session.SessionChatController
import com.xxt.zl.ai.ruyiremote.session.SessionSummary
import com.xxt.zl.ai.ruyiremote.ui.components.EmptyState
import com.xxt.zl.ai.ruyiremote.ui.components.GhostButton
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphMenu
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphPlus
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphRefresh
import com.xxt.zl.ai.ruyiremote.ui.components.InlineError
import com.xxt.zl.ai.ruyiremote.ui.components.NoticeBar
import com.xxt.zl.ai.ruyiremote.ui.components.Pill
import com.xxt.zl.ai.ruyiremote.ui.components.PressableCircle
import com.xxt.zl.ai.ruyiremote.ui.components.SkeletonRow
import com.xxt.zl.ai.ruyiremote.ui.components.TypingDots
import com.xxt.zl.ai.ruyiremote.ui.formatWhen
import com.xxt.zl.ai.ruyiremote.ui.screenFrame
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space

/**
 * 一台电脑的工作区：已连接、但还没选会话时的落脚页。
 *
 * 这里是「切主机」的落点。之所以不是直接跳进某条会话：会话 id 属于具体主机，
 * 新连接里拿旧 id 去 follow 只会得到空壳或错误。先停在工作区，让用户看清这台
 * 电脑上有什么，再由他主动选一条会话（或新建）。
 *
 * 左上角固定是抽屉入口：抽屉里可以换电脑、刷新列表、看当前会话的后台任务。
 */
@Composable
fun HostWorkspaceScreen(
    hostName: String,
    hostOnline: Boolean,
    connected: Boolean,
    connecting: Boolean,
    switching: Boolean,
    transportNotice: String?,
    chat: SessionChatController?,
    onOpenDrawer: () -> Unit,
    onRetryConnect: (() -> Unit)?,
    onSelectSession: (SessionSummary) -> Unit,
) {
    val p = LocalRuyiPalette.current
    val state: ChatState? = chat?.state?.collectAsState()?.value

    Column(Modifier.screenFrame()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.s, end = Space.s, top = Space.s, bottom = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PressableCircle(onClick = onOpenDrawer, label = "打开主机与会话抽屉") {
                GlyphMenu(size = 20.dp, tint = p.text)
            }
            Spacer(Modifier.width(Space.s))
            Column(Modifier.weight(1f)) {
                Text(
                    hostName,
                    style = MaterialTheme.typography.titleLarge,
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        connecting -> "正在建立加密通道…"
                        connected -> "已连接 · 远程"
                        hostOnline -> "未连接 · 在线"
                        else -> "未连接 · 离线"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        connected -> p.success
                        connecting -> p.warning
                        else -> p.textTertiary
                    },
                )
            }
            if (connected && chat != null) {
                PressableCircle(onClick = chat::loadSessions, label = "刷新会话列表") {
                    GlyphRefresh(
                        tint = if (state?.sessionsLoading == true) p.textTertiary else p.textSecondary,
                    )
                }
            }
        }

        if (!connected && transportNotice != null) {
            Box(Modifier.padding(horizontal = Space.page, vertical = Space.xs)) {
                NoticeBar(transportNotice)
            }
        }

        when {
            switching -> ConnectingBody(hostName = hostName)

            !connected || chat == null || state == null -> DisconnectedBody(
                online = hostOnline,
                onOpenDrawer = onOpenDrawer,
                onRetryConnect = onRetryConnect,
            )

            else -> ConnectedBody(
                chat = chat,
                state = state,
                onSelectSession = onSelectSession,
            )
        }
    }
}

@Composable
private fun ConnectingBody(hostName: String) {
    val p = LocalRuyiPalette.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        TypingDots(color = p.textTertiary)
        Text(
            "正在切换到 $hostName…",
            style = MaterialTheme.typography.bodyMedium,
            color = p.text,
        )
        Text(
            "旧电脑的会话已经清空；连上后会列出这台电脑的会话。",
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
        )
    }
}

@Composable
private fun DisconnectedBody(
    online: Boolean,
    onOpenDrawer: () -> Unit,
    onRetryConnect: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        EmptyState(
            title = if (online) "还没有连接到这台电脑" else "这台电脑离线",
            body = if (online) {
                "从「我的电脑」里选择一台在线设备。连接成功后会在这里列出它的会话。"
            } else {
                "请确认桌面端已打开并开启了「远程访问」，或在抽屉里改选另一台电脑。"
            },
            actionLabel = "选择电脑",
            onAction = onOpenDrawer,
        )
        if (onRetryConnect != null) {
            GhostButton(
                text = "重新连接这台电脑",
                onClick = onRetryConnect,
                modifier = Modifier.padding(horizontal = Space.page),
            )
        }
    }
}

@Composable
private fun ConnectedBody(
    chat: SessionChatController,
    state: ChatState,
    onSelectSession: (SessionSummary) -> Unit,
) {
    val p = LocalRuyiPalette.current
    val sessions = state.sessions
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.page)) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.card)
                    .clickable(enabled = !state.creating) {
                        chat.createSession(onOpened = onSelectSession)
                    }
                    .padding(horizontal = Space.l, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphPlus(size = 19.dp, tint = p.accent)
                Spacer(Modifier.width(Space.s))
                Text(
                    if (state.creating) "正在新建会话…" else "新建会话",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = p.text,
                )
            }
            state.sessionsError?.let {
                Spacer(Modifier.height(Space.s))
                InlineError(it, retryLabel = "重新读取", onRetry = chat::loadSessions)
            }
            state.createError?.let {
                Spacer(Modifier.height(Space.s))
                InlineError(it, retryLabel = "重试新建", onRetry = { chat.createSession(onOpened = onSelectSession) })
            }
        }

        Spacer(Modifier.height(Space.l))
        Row(Modifier.padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "这台电脑上的会话",
                style = MaterialTheme.typography.labelMedium,
                color = p.textTertiary,
                modifier = Modifier.weight(1f),
            )
            if (state.sessionsLoading) TypingDots(color = p.textTertiary)
            else Text("${sessions.size} 条", style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
        }
        Spacer(Modifier.height(Space.s))

        when {
            state.sessionsLoading && sessions.isEmpty() -> Column(Modifier.padding(horizontal = Space.page)) {
                repeat(4) { SkeletonRow() }
            }

            sessions.isEmpty() -> EmptyState(
                title = "这台电脑还没有会话",
                body = "点上面的「新建会话」，或直接在电脑上继续原来的会话。",
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = Space.page,
                    end = Space.page,
                    top = Space.xs,
                    bottom = Space.xxxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                items(sessions, key = { it.id }) { session ->
                    WorkspaceSessionRow(session = session, onClick = { onSelectSession(session) })
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSessionRow(session: SessionSummary, onClick: () -> Unit) {
    val p = LocalRuyiPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (session.running) {
                    Spacer(Modifier.width(Space.s))
                    Pill("运行中", p.success)
                } else if (session.blank) {
                    Spacer(Modifier.width(Space.s))
                    Pill("空会话", p.textTertiary)
                }
            }
            session.cwd?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = p.textTertiary,
                    fontFamily = Mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(Space.s))
        Text(
            formatWhen(session.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = p.textTertiary,
            maxLines = 1,
        )
    }
}
