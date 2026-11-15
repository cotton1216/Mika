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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.xxt.zl.ai.ruyiremote.ui.components.PlainField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.session.ChatState
import com.xxt.zl.ai.ruyiremote.session.SessionChatController
import com.xxt.zl.ai.ruyiremote.session.SessionSummary
import com.xxt.zl.ai.ruyiremote.ui.components.DshTopBar
import com.xxt.zl.ai.ruyiremote.ui.components.EmptyState
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphPlus
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphRefresh
import com.xxt.zl.ai.ruyiremote.ui.components.InlineError
import com.xxt.zl.ai.ruyiremote.ui.components.Pill
import com.xxt.zl.ai.ruyiremote.ui.components.PressableCircle
import com.xxt.zl.ai.ruyiremote.ui.components.SkeletonRow
import com.xxt.zl.ai.ruyiremote.ui.formatWhen
import com.xxt.zl.ai.ruyiremote.ui.screenFrame
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space

/**
 * 这台电脑上的会话列表。
 *
 * 标题用 `displayTitle`（没有标题时退到目录名，再退到短 id）—— 桌面端给会话起
 * 名字是可选的，大量会话其实只有 cwd，所以目录名才是用户真正的记忆锚点，这里把它
 * 显示成第二行。
 */
@Composable
fun SessionsScreen(
    chat: SessionChatController?,
    hostName: String,
    onBack: () -> Unit,
    onOpen: (SessionSummary) -> Unit,
) {
    val p = LocalRuyiPalette.current
    if (chat == null) {
        Column(Modifier.screenFrame()) {
            DshTopBar(title = "会话", subtitle = hostName, onBack = onBack)
            EmptyState(
                title = "连接已断开",
                body = "回到上一页重新连接这台电脑。",
                actionLabel = "返回",
                onAction = onBack,
            )
        }
        return
    }

    val state by chat.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val visibleSessions = state.sessions.filter {
        query.isBlank() || it.displayTitle.contains(query.trim(), ignoreCase = true) ||
            it.cwd.orEmpty().contains(query.trim(), ignoreCase = true)
    }

    Column(Modifier.screenFrame()) {
        DshTopBar(
            title = "会话",
            subtitle = hostName,
            onBack = onBack,
            actions = {
                // 新建成功后自动进入对话页：点了「+」却什么都不跳，用户会以为没反应。
                PressableCircle(onClick = { chat.createSession(onOpened = onOpen) }, label = "新建会话") {
                    GlyphPlus(tint = if (state.creating) p.textTertiary else p.accent)
                }
                PressableCircle(onClick = chat::loadSessions, label = "刷新会话列表") {
                    GlyphRefresh(tint = if (state.sessionsLoading) p.textTertiary else p.textSecondary)
                }
            },
        )

        Column(Modifier.padding(horizontal = Space.page), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            PlainField(value = query, onValueChange = { query = it }, placeholder = "搜索会话标题或工作目录")
            state.sessionsError?.let {
                InlineError(it, retryLabel = "重新读取", onRetry = chat::loadSessions)
            }
            state.createError?.let {
                InlineError(it, retryLabel = "重试新建", onRetry = { chat.createSession(onOpened = onOpen) })
            }
        }

        when {
            state.sessionsLoading && state.sessions.isEmpty() -> Column(Modifier.padding(horizontal = Space.page)) {
                repeat(4) { SkeletonRow() }
            }

            state.sessions.isEmpty() && state.sessionsError == null -> EmptyState(
                title = "还没有会话",
                body = "点右上角的 + 新建一个，或直接在电脑上继续你原来的会话。",
                actionLabel = "新建会话",
                onAction = { chat.createSession(onOpened = onOpen) },
            )

            visibleSessions.isEmpty() && query.isNotBlank() -> EmptyState(
                title = "没有找到匹配的会话",
                body = "换个关键词，或清空搜索查看全部会话。",
                actionLabel = "清空搜索",
                onAction = { query = "" },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = Space.page,
                    end = Space.page,
                    top = Space.s,
                    bottom = Space.xxxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                items(visibleSessions, key = { it.id }) { session ->
                    SessionRow(session = session, onClick = { onOpen(session) })
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onClick: () -> Unit) {
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
                    // 空会话和「有内容但没在跑」是两种不同状态，混在一起会让人点进去
                    // 才发现什么都没有。Host 的 blank 就是这一轮的判据。
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
        Box(contentAlignment = Alignment.CenterEnd) {
            Text(
                formatWhen(session.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
                maxLines = 1,
            )
        }
    }
}
