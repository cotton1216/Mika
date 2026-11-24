package com.xxt.zl.ai.ruyiremote.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.ui.AccountPhase
import com.xxt.zl.ai.ruyiremote.ui.RemoteSessionController
import com.xxt.zl.ai.ruyiremote.ui.RemoteSso
import com.xxt.zl.ai.ruyiremote.ui.SignInController
import com.xxt.zl.ai.ruyiremote.ui.SignInPhase
import com.xxt.zl.ai.ruyiremote.ui.components.DshCard
import com.xxt.zl.ai.ruyiremote.ui.components.DshTopBar
import com.xxt.zl.ai.ruyiremote.ui.components.GhostButton
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphChevron
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphMonitor
import com.xxt.zl.ai.ruyiremote.ui.components.InlineError
import com.xxt.zl.ai.ruyiremote.ui.components.NoticeBar
import com.xxt.zl.ai.ruyiremote.ui.components.PairingCodeField
import com.xxt.zl.ai.ruyiremote.ui.components.PlainField
import com.xxt.zl.ai.ruyiremote.ui.components.PrimaryButton
import com.xxt.zl.ai.ruyiremote.ui.describeSignIn
import com.xxt.zl.ai.ruyiremote.ui.screenFrame
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Mono
import com.xxt.zl.ai.ruyiremote.ui.theme.Space

/**
 * 首屏：只有一个任务 —— 把 8 位配对码填进来。
 *
 * 没有账号密码、没有 SSO、没有中继地址。它们不是不重要，而是**第一次打开应用的人
 * 只想知道「现在我该做什么」**。次要入口收在底部一行「其他登录方式」里，点进去是
 * 独立一屏（[AlternateSignInScreen]）。
 */
@Composable
fun ConnectScreen(
    session: RemoteSessionController,
    signIn: SignInController,
    onOpenAlternatives: () -> Unit,
    /**
     * 回宿主（如意 AI）走如意账号登录。为 null 时不渲染这个入口 —— 独立 App 自己就是根，
     * 没有「别处」可回。
     */
    onBackToHostSignIn: (() -> Unit)? = null,
) {
    val p = LocalRuyiPalette.current
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    val failure = session.phase as? AccountPhase.Failed
    // 宿主（如意 AI）那次联动留下的状态：注册失败，或随如意 AI 退出被主动清除。两个场景
    // 都要说明白 —— 这一屏没有如意账号入口（宿主传了 showSso=false），不写清楚
    // 用户只会觉得「明明登录过，怎么又要登录」。独立 App 不写这个标记，天然不显示。
    //
    // 订阅 RemoteSso.state 而不是 remember 快照：登录/退出/注册失败都在同进程改标记，
    // 登录页常驻组合、首帧之后不再重建，remember 会永远停在首帧那个值。
    val ssoState by RemoteSso.state.collectAsState()
    // 冷启动/首帧把落盘值同步进流；之后由 signIn/signOut 实时推。
    LaunchedEffect(Unit) { RemoteSso.lastState(context) }

    Column(
        modifier = Modifier
            .screenFrame()
            .padding(horizontal = Space.page),
    ) {
        // 内容区在剩余高度里垂直居中：首屏只做一件事，把它放在视线中段，而不是顶端
        // 贴一条、下面空一半。空间不够（键盘弹起 / 小屏）时 heightIn 不再生效，退化成
        // 普通滚动列表，不会被 IME 挤掉「连接」按钮。
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(vertical = Space.xxl),
                verticalArrangement = Arrangement.Center,
            ) {
                // 品牌区
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Box(
                        Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(p.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlyphMonitor(size = 30.dp, tint = p.onAccent)
                    }
                    Text("如意远程", style = MaterialTheme.typography.displaySmall, color = p.text)
                    Text(
                        "电脑上的如意会话，手机上接着看、接着发。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textSecondary,
                    )
                }

                Spacer(Modifier.height(Space.xxl))

                session.signedOutNotice?.let {
                    NoticeBar(it)
                    Spacer(Modifier.height(Space.l))
                }

                ssoState?.let { state ->
                    NoticeBar(ssoStateText(state))
                    // 提示说「回如意 AI 登录一次」，那就得有一条能真的回去的路：这一屏没有
                    // 如意账号入口（宿主传了 showSso=false），只有配对码和账号密码。
                    // 光写文字等于让用户自己去翻设置。
                    if (onBackToHostSignIn != null) {
                        Spacer(Modifier.height(Space.m))
                        GhostButton(
                            text = "回如意 AI 登录",
                            onClick = onBackToHostSignIn,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(Space.l))
                }

                Text(
                    "配对码",
                    style = MaterialTheme.typography.labelMedium,
                    color = p.textTertiary,
                    modifier = Modifier.padding(start = Space.xs, bottom = Space.s),
                )
                PairingCodeField(
                    value = code,
                    onValueChange = { code = it },
                    enabled = !session.busy,
                    onDone = { if (code.length == 8 && !session.busy) session.loginWithCode(code) },
                )
                Spacer(Modifier.height(Space.m))
                Text(
                    "在电脑上打开如意 AI →「远程访问」→ 生成配对码。一次性使用，过期要重新生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                )

                Spacer(Modifier.height(Space.xl))
                PrimaryButton(
                    text = if (session.busy) "正在连接电脑…" else "连接",
                    onClick = { session.loginWithCode(code) },
                    enabled = code.length == 8,
                    loading = session.busy,
                )

                if (failure != null) {
                    Spacer(Modifier.height(Space.l))
                    InlineError(
                        message = failure.message,
                        code = failure.code,
                        retryLabel = "知道了，重新输入",
                        onRetry = session::backToSignIn,
                    )
                }

                Spacer(Modifier.height(Space.xxl))
                OtherWays(onClick = onOpenAlternatives)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Space.l),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(p.success, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(Space.s))
            Text(
                "中继 ${session.serverUrl.removePrefix("https://").removePrefix("http://")}",
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
                fontFamily = Mono,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun OtherWays(onClick: () -> Unit) {
    val p = LocalRuyiPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = Space.m, horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "没有配对码？用账号登录",
            style = MaterialTheme.typography.labelLarge,
            color = p.textSecondary,
            modifier = Modifier.weight(1f),
        )
        GlyphChevron(tint = p.textTertiary)
    }
}

/**
 * 次要登录方式。
 *
 * 单独一屏而不是塞回首屏：如意账号要跳一次系统浏览器，比配对码多两步操作，
 * 仍按次要入口摆；但「中继不认 AI Server 令牌」那条边界已经不存在了 ——
 * 授权码交给中继换票，拿到的是中继自己的会话（见 [SignInController]）。
 *
 * [showSso] 为假时不出「如意账号」那一张卡：宿主 App 自己已经有如意登录时，
 * 这里再摆一个入口就是第二个登录页。设备由宿主那次登录顺带注册好，用户通常
 * 根本走不到这一屏。
 */
@Composable
fun AlternateSignInScreen(
    session: RemoteSessionController,
    signIn: SignInController,
    onBack: () -> Unit,
    showSso: Boolean = true,
) {
    val p = LocalRuyiPalette.current
    val failure = session.phase as? AccountPhase.Failed

    Column(Modifier.screenFrame()) {
        DshTopBar(title = "其他登录方式", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            if (failure != null) {
                InlineError(failure.message, code = failure.code, retryLabel = "清除错误", onRetry = session::backToSignIn)
            }
            PasswordCard(session)
            if (showSso) SsoCard(signIn)
            Text(
                if (showSso) "两条都需要电脑端用同一账号登录并打开远程访问。"
                else "需要电脑端用同一账号登录并打开远程访问。",
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
                modifier = Modifier.padding(horizontal = Space.xs),
            )
        }
    }
}

@Composable
private fun PasswordCard(session: RemoteSessionController) {
    val p = LocalRuyiPalette.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    DshCard {
        Text("账号密码", style = MaterialTheme.typography.titleMedium, color = p.text)
        Text(
            "用中继上已有的账号登录，登录后会把本机注册为受信设备。",
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
        )
        Spacer(Modifier.height(Space.xs))
        PlainField(
            value = email,
            onValueChange = { email = it },
            placeholder = "账号 / 邮箱",
            keyboardType = KeyboardType.Email,
        )
        PlainField(
            value = password,
            onValueChange = { password = it },
            placeholder = "密码",
            masked = true,
            keyboardType = KeyboardType.Password,
        )
        Spacer(Modifier.height(Space.xs))
        PrimaryButton(
            text = "登录",
            onClick = { session.loginWithPassword(email, password) },
            enabled = email.isNotBlank() && password.isNotEmpty(),
            loading = session.busy,
        )
        Text(
            "凭据保存在系统密钥库，明文密码不落盘。",
            style = MaterialTheme.typography.labelSmall,
            color = p.textTertiary,
        )
    }
}

@Composable
private fun SsoCard(signIn: SignInController) {
    val p = LocalRuyiPalette.current
    DshCard {
        Text("如意账号", style = MaterialTheme.typography.titleMedium, color = p.text)
        Text(
            "跳转浏览器完成统一登录，回来后由中继签发账号会话并注册本机。",
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
        )
        when (val phase = signIn.phase) {
            SignInPhase.Idle -> PrimaryButton(text = "打开如意登录页", onClick = signIn::start)

            SignInPhase.OpeningBrowser, SignInPhase.WaitingForBrowser, SignInPhase.Exchanging -> {
                Text(
                    when (phase) {
                        SignInPhase.OpeningBrowser -> "正在打开浏览器…"
                        SignInPhase.WaitingForBrowser -> "在浏览器里完成登录后会自动跳回"
                        else -> "正在换取账号令牌…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                )
                GhostButton(text = "取消", onClick = signIn::cancel, modifier = Modifier.fillMaxWidth())
            }

            is SignInPhase.SignedIn -> Text(
                "如意已登录，正在向中继注册本机…",
                style = MaterialTheme.typography.bodySmall,
                color = p.textSecondary,
            )

            is SignInPhase.Failed -> {
                InlineError(describeSignIn(phase.failure, phase.code))
                GhostButton(text = "重新登录", onClick = signIn::start, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * 把 [RemoteSso.lastState] 的粗分类翻成人话。
 *
 * 关键是**指向同一件事**：恢复登录只有一条路 —— 回如意 AI 用如意账号登录一次，这里
 * 会自动重新注册。不要写成「在这里登录」：这一屏没有如意账号入口，那会把用户引到
 * 配对码/账号密码那条岔路，等于制造第二个登录。
 */
private fun ssoStateText(state: String): String = when (state) {
    "signed_out" -> "已随如意 AI 退出而清除本机登录。回到如意 AI 用如意账号登录一次，这里会自动恢复。"
    "unreachable" -> "上次随如意 AI 登录自动注册未完成：连不上中继。回到如意 AI 重新登录一次即可恢复。"
    "rejected" -> "上次随如意 AI 登录自动注册未完成：账号校验没通过。回到如意 AI 重新登录一次即可恢复。"
    else -> "上次随如意 AI 登录自动注册未完成。回到如意 AI 重新登录一次即可恢复。"
}
