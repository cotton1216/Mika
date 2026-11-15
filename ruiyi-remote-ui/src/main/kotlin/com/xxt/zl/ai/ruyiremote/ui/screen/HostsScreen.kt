package com.xxt.zl.ai.ruyiremote.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.account.HostTrust
import com.xxt.zl.ai.ruyiremote.account.RemoteHost
import com.xxt.zl.ai.ruyiremote.ui.RemoteSessionController
import com.xxt.zl.ai.ruyiremote.ui.components.DshCard
import com.xxt.zl.ai.ruyiremote.ui.components.DshTopBar
import com.xxt.zl.ai.ruyiremote.ui.components.EmptyState
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphChevron
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphMonitor
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphRefresh
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphTune
import com.xxt.zl.ai.ruyiremote.ui.components.InlineError
import com.xxt.zl.ai.ruyiremote.ui.components.NoticeBar
import com.xxt.zl.ai.ruyiremote.ui.components.Pill
import com.xxt.zl.ai.ruyiremote.ui.components.PressableCircle
import com.xxt.zl.ai.ruyiremote.ui.components.SkeletonRow
import com.xxt.zl.ai.ruyiremote.ui.components.StatusDot
import com.xxt.zl.ai.ruyiremote.ui.screenFrame
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space
import com.xxt.zl.ai.ruyiremote.ui.trustLine

/**
 * 「我的电脑」——登录之后的落脚页。
 *
 * 一屏只回答一个问题：**现在能连哪台电脑**。所以每行只留名称、在线状态、以及
 * 一句身份提示；指纹、版本、能力这些核对用的信息全部下放到详情页。
 */
@Composable
fun HostsScreen(
    session: RemoteSessionController,
    onOpenHost: (RemoteHost) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val p = LocalRuyiPalette.current

    Column(Modifier.screenFrame()) {
        DshTopBar(
            title = "我的电脑",
            // 副标题说「有几台、几台在线」而不是回显账号名 —— 账号名在设置页已经有了，
            // 这里更该回答「我能不能连上」。
            subtitle = when {
                session.hosts.isEmpty() -> null
                else -> "${session.hosts.size} 台设备 · ${session.hosts.count { it.online }} 台在线"
            },
            actions = {
                PressableCircle(onClick = session::refreshHosts, label = "刷新电脑列表") {
                    GlyphRefresh(tint = if (session.hostsLoading) p.textTertiary else p.textSecondary)
                }
                PressableCircle(onClick = onOpenSettings, label = "设置") { GlyphTune(tint = p.textSecondary) }
            },
        )

        val connectedName = session.connectedHost?.name

        Column(Modifier.padding(horizontal = Space.page), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            if (connectedName != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.accentSoft)
                        .padding(horizontal = Space.m, vertical = Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(online = true)
                    Spacer(Modifier.width(Space.s))
                    Text(
                        "已连接 $connectedName",
                        style = MaterialTheme.typography.labelLarge,
                        color = p.onAccentSoft,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "断开",
                        style = MaterialTheme.typography.labelMedium,
                        color = p.onAccentSoft.copy(alpha = 0.75f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = session::disconnect)
                            .padding(horizontal = Space.s, vertical = Space.xs),
                    )
                }
            }

            // 通道是被对端/网络断掉的：这里说清楚「为什么回来了」，而不是静默弹回列表。
            if (connectedName == null) {
                session.transportNotice?.let { NoticeBar(it) }
            }

            session.hostsError?.let {
                InlineError(it, retryLabel = "重试", onRetry = session::refreshHosts)            }
        }

        when {
            session.hostsLoading && session.hosts.isEmpty() -> Column(Modifier.padding(horizontal = Space.page)) {
                repeat(3) { SkeletonRow() }
            }

            session.hosts.isEmpty() && session.hostsError == null -> EmptyState(
                title = "还没有电脑",
                body = "在电脑上登录同一个账号，打开如意 AI 的「远程访问」，它就会出现在这里。",
                actionLabel = "重新检查",
                onAction = session::refreshHosts,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Space.page,
                    end = Space.page,
                    top = Space.l,
                    bottom = Space.xxxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                items(session.hosts, key = { it.id }) { host ->
                    HostCard(
                        host = host,
                        connected = session.connectedHost?.id == host.id,
                        connecting = session.connectingHostId == host.id,
                        onClick = { onOpenHost(host) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HostCard(
    host: RemoteHost,
    connected: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    DshCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (host.online) p.accentSoft else p.cardPressed),
                contentAlignment = Alignment.Center,
            ) {
                GlyphMonitor(
                    size = 24.dp,
                    tint = if (host.online) p.onAccentSoft else p.textTertiary,
                )
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    host.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            connecting -> "连接中…"
                            connected -> "已连接"
                            host.online -> "在线"
                            else -> "离线"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            connected || host.online -> p.success
                            else -> p.textTertiary
                        },
                    )
                    host.harnessVersion?.let {
                        Text(
                            " · 如意 $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.textTertiary,
                            fontFamily = Mono,
                            maxLines = 1,
                        )
                    }
                }
                if (host.trust == HostTrust.keyChanged) {
                    Text(
                        trustLine(host.trust),
                        style = MaterialTheme.typography.labelSmall,
                        color = p.danger,
                    )
                }
            }
            if (connected) {
                Pill("已连接", p.success)
                Spacer(Modifier.width(Space.s))
            }
            GlyphChevron(tint = p.textTertiary)
        }
    }
}
