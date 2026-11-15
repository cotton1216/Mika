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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.xxt.zl.ai.ruyiremote.ui.RemoteSessionController
import com.xxt.zl.ai.ruyiremote.ui.components.EmptyState
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphChevron
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphPlus
import com.xxt.zl.ai.ruyiremote.ui.components.InlineError
import com.xxt.zl.ai.ruyiremote.ui.components.NoticeBar
import com.xxt.zl.ai.ruyiremote.ui.components.Pill
import com.xxt.zl.ai.ruyiremote.ui.components.SkeletonRow
import com.xxt.zl.ai.ruyiremote.ui.components.StatusDot
import com.xxt.zl.ai.ruyiremote.ui.components.TypingDots
import com.xxt.zl.ai.ruyiremote.ui.formatWhen
import com.xxt.zl.ai.ruyiremote.ui.screenFrame
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space

/**
 * 登录后的首页：会话列表（远程任务落脚页）。
 *
 * 不再把「我的电脑」选主机当首页——用户打开如意 Tab 想看的是这台电脑上正在做的事，
 * 而不是先被问「连哪台」。所以首页直接落会话列表；顶部的「主机列表」入口负责切电脑，
 * 设置也从这条路上进（点主机列表 → 抽屉顶栏的齿轮）。
 *
 * 不设顶栏：如意与愿是平级 Tab，用「← 返回」箭头表达「切回如愿」有歧义（看着像返回上一页）。
 * Tab 切换交给悬浮球，退出到如愿交给系统返回键；会话详情、设置等二级页仍有自己的返回箭头。
 * 没连电脑时给一句引导，让用户去点主机列表。
 */
@Composable
fun HomeScreen(
    session: RemoteSessionController,
    chat: SessionChatController?,
    onOpenHostList: () -> Unit,
    onSelectSession: (SessionSummary) -> Unit,
) {
    val state: ChatState? = chat?.state?.collectAsState()?.value

    val connectedHost = session.connectedHost
    val connectingHostId = session.connectingHostId
    val connected = connectedHost != null && session.connection != null
    val connecting = connectingHostId != null

    val hostName = connectedHost?.name
        ?: session.hosts.firstOrNull { it.id == connectingHostId }?.name
        ?: "选择电脑"

    Column(Modifier.screenFrame()) {
        Column(
            Modifier.padding(start = Space.page, end = Space.page, top = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            HostListEntry(
                hostName = hostName,
                subtitle = hostEntrySubtitle(session, connected, connecting),
                online = connected || connecting || session.hosts.any { it.online },
                onClick = onOpenHostList,
            )

            if (!connected && session.transportNotice != null) {
                NoticeBar(session.transportNotice!!)
            }
        }

        when {
            connecting -> ConnectingHint(hostName = hostName)

            !connected || chat == null || state == null -> {
                EmptyState(
                    title = "还没有连接电脑",
                    body = "点上方「主机列表」选一台在线设备。连接成功后，这里会列出这台电脑上的会话。",
                    actionLabel = "选择电脑",
                    onAction = onOpenHostList,
                )
            }

            else -> SessionList(
                chat = chat,
                state = state,
                onSelectSession = onSelectSession,
            )
        }
    }
}

private fun hostEntrySubtitle(session: RemoteSessionController, connected: Boolean, connecting: Boolean): String = when {
    connecting -> "连接中…"
    connected -> "已连接 · 在线"
    session.hosts.isEmpty() -> "还没有电脑"
    else -> "${session.hosts.count { it.online }} 台在线"
}

@Composable
private fun HostListEntry(
    hostName: String,
    subtitle: String,
    online: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(online = online)
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "主机列表",
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
            )
            Text(
                hostName,
                style = MaterialTheme.typography.titleMedium,
                color = p.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = p.textTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.width(Space.s))
        GlyphChevron(tint = p.textTertiary)
    }
}

@Composable
private fun ConnectingHint(hostName: String) {
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
            "连上后会列出这台电脑上的会话。",
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
        )
    }
}

@Composable
private fun SessionList(
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
                    HomeSessionRow(session = session, onClick = { onSelectSession(session) })
                }
            }
        }
    }
}

@Composable
private fun HomeSessionRow(session: SessionSummary, onClick: () -> Unit) {
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
