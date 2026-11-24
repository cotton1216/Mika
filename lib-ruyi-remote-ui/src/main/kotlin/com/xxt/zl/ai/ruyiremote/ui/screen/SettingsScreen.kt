package com.xxt.zl.ai.ruyiremote.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xxt.zl.ai.ruyiremote.keepalive.RemoteKeepAliveService
import com.xxt.zl.ai.ruyiremote.ui.AccountPhase
import com.xxt.zl.ai.ruyiremote.ui.RemoteSessionController
import com.xxt.zl.ai.ruyiremote.ui.components.DshCard
import com.xxt.zl.ai.ruyiremote.ui.components.DshTopBar
import com.xxt.zl.ai.ruyiremote.ui.components.GhostButton
import com.xxt.zl.ai.ruyiremote.ui.components.KeyValueRow
import com.xxt.zl.ai.ruyiremote.ui.components.Pill
import com.xxt.zl.ai.ruyiremote.ui.components.SectionLabel
import com.xxt.zl.ai.ruyiremote.ui.screenFrame
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space

/**
 * 设置：账号、中继、后台保活。
 *
 * 这些东西以前挤在首屏底部，属于「一次配置、长期不看」的信息。放进独立页面后，
 * 首屏才有空间只放配对码。
 */
@Composable
fun SettingsScreen(
    session: RemoteSessionController,
    onBack: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    val phase = session.phase as? AccountPhase.SignedIn
    var confirmLogout by remember { mutableStateOf(false) }
    if (confirmLogout) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录？") },
            text = { Text("将断开当前连接，并清除本机登录凭据和已核对的电脑身份。下次需要重新配对。电脑上的会话不会删除。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmLogout = false
                    session.logout()
                }) { Text("退出登录", color = p.danger) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }

    Column(Modifier.screenFrame()) {
        DshTopBar(title = "设置", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.page)
                .padding(bottom = Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            if (phase != null) {
                Column {
                    SectionLabel("账号")
                    DshCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(session.accountLabel(phase.user))
                            Spacer(Modifier.width(Space.m))
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    session.accountLabel(phase.user),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = p.text,
                                )
                                Text(
                                    "设备 ${phase.user.deviceId.take(8)}…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = p.textTertiary,
                                    fontFamily = Mono,
                                )
                            }
                            Pill("已登录", p.success)
                        }
                    }
                }
            }

            Column {
                SectionLabel("连接")
                DshCard {
                    KeyValueRow("中继地址", session.serverUrl, mono = true)
                    val connected = session.connectedHost
                    KeyValueRow(
                        "当前",
                        if (connected != null) "已连接 ${connected.name}" else "未连接任何电脑",
                        valueColor = if (connected != null) p.success else p.textSecondary,
                    )
                }
            }

            KeepAliveCard()

            // 破坏性动作单独放最后，不和「账号信息」混在同一张卡里 —— 混在一起时
            // 用户在滚动中很容易误触，而且卡片视觉重心被一个红字按钮抢走。
            if (phase != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    GhostButton(
                        text = "退出登录",
                        onClick = { confirmLogout = true },
                        modifier = Modifier.fillMaxWidth(),
                        tone = p.danger,
                    )
                    Text(
                        "退出会清掉本机保存的凭据与已核对的电脑身份，下次要重新配对。",
                        style = MaterialTheme.typography.labelSmall,
                        color = p.textTertiary,
                        modifier = Modifier.padding(horizontal = Space.xs),
                    )
                }
            }

            Text(
                "如意远程 · 原生客户端 0.1.0",
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Space.s),
            )
        }
    }
}

/** 账号头像：没有真实头像，就用名字首字做占位，比默认灰人看着像样。 */
@Composable
private fun Avatar(label: String) {
    val p = LocalRuyiPalette.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(p.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.take(1),
            style = MaterialTheme.typography.titleLarge,
            color = p.onAccentSoft,
        )
    }
}

@Composable
private fun KeepAliveCard() {
    val p = LocalRuyiPalette.current
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(RemoteKeepAliveService.running) }
    var notificationBlocked by remember { mutableStateOf(false) }

    // 服务的 running 是异步翻的（见 RemoteKeepAliveService.running 的注释），
    // 所以不能点完立刻读回，必须订阅它自己报出来的状态。
    DisposableEffect(Unit) {
        val unsubscribe = RemoteKeepAliveService.observe { enabled = it }
        onDispose { unsubscribe() }
    }

    // API 33+ 通知是运行时权限。不申请保活照样生效（前台服务优先级与进程冻结保护
    // 不依赖通知），但通知栏一片空白，用户无从判断它是否在跑 —— 真机上很容易被
    // 当成「开关没反应」。
    val requestNotification = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationBlocked = !granted
        startKeepAlive(context)
    }

    Column {
        SectionLabel("常驻")
        DshCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("后台保活", style = MaterialTheme.typography.titleMedium, color = p.text)
                    Text(
                        "连接电脑时打开，避免 App 退到后台被系统冻结、连接静默断开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { next ->
                        if (!next) {
                            runCatching { RemoteKeepAliveService.stop(context) }
                            return@Switch
                        }
                        if (needsNotificationPermission(context)) {
                            requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startKeepAlive(context)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = p.accent,
                        uncheckedTrackColor = p.cardPressed,
                        uncheckedBorderColor = p.hairline,
                    ),
                )
            }
            if (notificationBlocked) {
                Text(
                    "系统已屏蔽本应用的通知：保活仍然生效，但通知栏看不到状态。",
                    style = MaterialTheme.typography.labelSmall,
                    color = p.danger,
                )
            }
        }
    }
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

private fun startKeepAlive(context: Context) {
    runCatching { RemoteKeepAliveService.start(context) }
}
