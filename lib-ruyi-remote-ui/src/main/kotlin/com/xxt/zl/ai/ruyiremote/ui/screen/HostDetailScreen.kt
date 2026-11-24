package com.xxt.zl.ai.ruyiremote.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.account.HostTrust
import com.xxt.zl.ai.ruyiremote.account.RemoteHost
import com.xxt.zl.ai.ruyiremote.ui.RemoteSessionController
import com.xxt.zl.ai.ruyiremote.ui.components.DshCard
import com.xxt.zl.ai.ruyiremote.ui.components.DshTopBar
import com.xxt.zl.ai.ruyiremote.ui.components.GhostButton
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphCheck
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphMonitor
import com.xxt.zl.ai.ruyiremote.ui.components.KeyValueRow
import com.xxt.zl.ai.ruyiremote.ui.components.NoticeBar
import com.xxt.zl.ai.ruyiremote.ui.components.PrimaryButton
import com.xxt.zl.ai.ruyiremote.ui.components.SectionLabel
import com.xxt.zl.ai.ruyiremote.ui.components.StatusDot
import com.xxt.zl.ai.ruyiremote.ui.groupFingerprint
import com.xxt.zl.ai.ruyiremote.ui.screenFrame
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space
import com.xxt.zl.ai.ruyiremote.ui.trustDetail
import com.xxt.zl.ai.ruyiremote.ui.trustLine

/**
 * 单台电脑。
 *
 * 这一屏是「核对 + 连接」的地方：指纹、信任状态、通道类型、能力清单都在这里，
 * 因为它们只有在**针对某台电脑**时才有意义。列表页不该背这些信息。
 */
@Composable
fun HostDetailScreen(
    session: RemoteSessionController,
    host: RemoteHost,
    onBack: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    val connection = session.connection
    val connected = session.connectedHost?.id == host.id && connection != null
    val connecting = session.connectingHostId == host.id

    Column(Modifier.screenFrame()) {
        DshTopBar(title = host.name, subtitle = if (host.online) "在线" else "离线", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.page)
                .padding(bottom = Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            // 从会话页被「通道断开」弹回来时，先说清原因，再让人重新连。
            if (!connected) {
                session.transportNotice?.let { NoticeBar(it) }
            }

            // 头部：设备缩略 + 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (host.online) p.accentSoft else p.cardPressed),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphMonitor(size = 32.dp, tint = if (host.online) p.onAccentSoft else p.textTertiary)
                }
                Spacer(Modifier.width(Space.l))
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(online = host.online)
                        Spacer(Modifier.width(Space.s))
                        Text(
                            when {
                                connecting -> "正在连接…"
                                connected -> "已连接"
                                host.online -> "在线，可以连接"
                                else -> "离线"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (host.online) p.success else p.textTertiary,
                        )
                    }
                    host.harnessVersion?.let {
                        Text(
                            "如意 $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.textTertiary,
                            fontFamily = Mono,
                        )
                    }
                }
            }

            // 连接区
            Column {
                SectionLabel("连接")
                DshCard {
                    if (connected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphCheck(tint = p.success)
                            Spacer(Modifier.width(Space.s))
                            Text(
                                "通道 ${connection?.transportMode ?: "—"} · 端到端加密",
                                style = MaterialTheme.typography.bodyMedium,
                                color = p.text,
                            )
                        }
                        connection?.capabilities?.takeIf { it.isNotEmpty() }?.let { caps ->
                            CapabilityChips(caps)
                        }
                        Spacer(Modifier.height(Space.xs))
                        PrimaryButton(text = "进入会话", onClick = onOpenSessions)
                        GhostButton(
                            text = "断开连接",
                            onClick = session::disconnect,
                            modifier = Modifier.fillMaxWidth(),
                            tone = p.textSecondary,
                        )
                    } else {
                        Text(
                            if (host.online) {
                                "连接后会先核对这台电脑的身份，再建立端到端加密通道。"
                            } else {
                                "这台电脑现在不在线。请确认桌面端已打开并开启了「远程访问」。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = p.textSecondary,
                        )
                        Spacer(Modifier.height(Space.xs))
                        PrimaryButton(
                            text = when {
                                connecting -> "正在建立加密通道…"
                                host.online -> "连接这台电脑"
                                else -> "离线"
                            },
                            onClick = { session.connect(host) },
                            enabled = host.online,
                            loading = connecting,
                        )
                    }
                    session.connectionError?.takeIf { session.connectingHostId == null }?.let {
                        InlineNote(text = it, danger = true)
                    }
                }
            }

            // 身份区
            Column {
                SectionLabel("身份")
                DshCard {
                    host.fingerprint?.let {
                        KeyValueRow(
                            label = "身份指纹",
                            value = groupFingerprint(it),
                            mono = true,
                            valueColor = if (host.trust == HostTrust.keyChanged) p.danger else p.text,
                        )
                        Spacer(Modifier.height(Space.xs))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            trustLine(host.trust),
                            style = MaterialTheme.typography.labelLarge,
                            color = when (host.trust) {
                                HostTrust.pinned -> p.success
                                HostTrust.keyChanged -> p.danger
                                else -> p.warning
                            },
                        )
                    }
                    Text(
                        trustDetail(host.trust),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                    )
                    if (host.trust != HostTrust.unpinned) {
                        Spacer(Modifier.height(Space.xs))
                        GhostButton(
                            text = if (host.trust == HostTrust.keyChanged) "重新信任这台电脑" else "忘记这台电脑",
                            onClick = { session.forgetHost(host) },
                            modifier = Modifier.fillMaxWidth(),
                            tone = p.danger,
                        )
                    }
                }
            }

            InlineNote(
                text = "指纹需要和桌面端「远程访问」页显示的那串一致。不一致说明中间有别的机器在冒充。",
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityChips(caps: List<String>) {
    val p = LocalRuyiPalette.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        caps.forEach { cap ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(p.cardPressed)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    cap,
                    style = MaterialTheme.typography.labelSmall,
                    color = p.textSecondary,
                    fontFamily = Mono,
                )
            }
        }
    }
}

/** 一段不带标题的旁注。用于解释性文字，避免每句话都套一层卡片。 */
@Composable
private fun InlineNote(text: String, danger: Boolean = false) {
    val p = LocalRuyiPalette.current
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (danger) p.danger else p.textTertiary,
        modifier = Modifier.padding(horizontal = Space.xs),
    )
}
