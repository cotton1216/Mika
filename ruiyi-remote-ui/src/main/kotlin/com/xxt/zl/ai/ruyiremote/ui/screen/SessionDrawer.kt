package com.xxt.zl.ai.ruyiremote.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.account.RemoteHost
import com.xxt.zl.ai.ruyiremote.session.RemoteJob
import com.xxt.zl.ai.ruyiremote.session.RemoteJobsController
import com.xxt.zl.ai.ruyiremote.session.RemoteJobsState
import com.xxt.zl.ai.ruyiremote.session.SessionSummary
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphChevron
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphPlus
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphRefresh
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphTune
import com.xxt.zl.ai.ruyiremote.ui.components.InlineError
import com.xxt.zl.ai.ruyiremote.ui.components.Pill
import com.xxt.zl.ai.ruyiremote.ui.components.RemoteJobsPanel
import com.xxt.zl.ai.ruyiremote.ui.components.SkeletonRow
import com.xxt.zl.ai.ruyiremote.ui.components.StatusDot
import com.xxt.zl.ai.ruyiremote.ui.components.TypingDots
import com.xxt.zl.ai.ruyiremote.ui.formatWhen
import com.xxt.zl.ai.ruyiremote.ui.theme.RuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * 抽屉的两个面板：会话导航，和当前会话的后台任务。
 *
 * 早先是两个并列页签，但任务流是 `job/list(sessionId)`、强依赖当前会话——未选会话时
 * 整页只剩一句引导。页签的并列关系暗示「两者同级同域」，实际是「导航」与「当前会话的
 * 运行时状态」。所以改成「会话是主面板，后台任务是它的二级页」，从会话页里的入口进入。
 */
private enum class DrawerPane { Sessions, Jobs }

/**
 * 全局会话抽屉：顶部切电脑，下面看这台电脑的会话 / 当前会话的后台任务。
 *
 * 它被提到 `DshApp` 一层，而不是留在 [ChatScreen] 里，是因为切主机后必须先离开
 * 旧会话、落到新主机的工作区，但抽屉本身要一直留在屏幕上——否则用户点完主机
 * 抽屉就消失，还得再找一次入口。抽屉只读状态、只回调动作，不含任何连接逻辑；
 * 生命周期跟着「抽屉是否展开」走。
 *
 * [switching] 为 true 时，主机与会话列表都处于「不可信」窗口：旧主机数据必须
 * 遮蔽，不能让人以为新主机已经有这些会话。
 */
@Composable
fun SessionDrawer(
    hosts: List<RemoteHost>,
    connectedHostId: String?,
    connectingHostId: String?,
    hostsLoading: Boolean,
    hostsError: String?,
    connectionError: String?,
    onRetryHost: (() -> Unit)?,
    onRefreshHosts: () -> Unit,
    onSelectHost: (RemoteHost) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHostDetail: (RemoteHost) -> Unit,
    sessions: List<SessionSummary>,
    sessionsLoading: Boolean,
    sessionsError: String?,
    switching: Boolean,
    currentSessionId: String?,
    onRefreshSessions: () -> Unit,
    onSelectSession: (SessionSummary) -> Unit,
    onNewSession: () -> Unit,
    jobs: RemoteJobsController?,
    jobsSessionId: String?,
    jobsSessionTitle: String?,
    onOpenAllSessions: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    var pane by remember { mutableStateOf(DrawerPane.Sessions) }
    var hostsExpanded by remember { mutableStateOf(false) }
    var pendingKill by remember { mutableStateOf<RemoteJob?>(null) }
    // 抽屉是从对话页浮上来的，输入框此时通常还在焦点上：不收键盘的话，抽屉下半屏
    // 会被 IME 一直占着，任务详情只能看到三四行（实机截图可见）。
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focus.clearFocus()
        keyboard?.hide()
    }
    val refreshHosts by rememberUpdatedState(onRefreshHosts)
    val refreshSessions by rememberUpdatedState(onRefreshSessions)
    // 主机列表在未连接时也要自动更新；抽屉被销毁就取消轮询。
    LaunchedEffect(Unit) {
        refreshHosts()
        while (true) {
            delay(20_000)
            refreshHosts()
        }
    }
    // 会话列表只属于已连接的这台电脑；切换期间不能拿旧控制器刷新。
    // loading 由控制器去重，刷新过程中保留已有列表，手动刷新钮仍可使用。
    LaunchedEffect(connectedHostId, switching) {
        if (connectedHostId != null && !switching) {
            refreshSessions()
            while (true) {
                delay(20_000)
                refreshSessions()
            }
        }
    }
    val jobsState: RemoteJobsState? = if (jobs != null) jobs.state.collectAsState().value else null

    // 切换中自动展开主机列表：用户刚点过某台主机，这时最需要看到「正在连哪台」，
    // 以及失败后立刻改选另一台。
    LaunchedEffect(connectingHostId, connectionError) {
        if (connectingHostId != null || connectionError != null) hostsExpanded = true
    }
    LaunchedEffect(jobsSessionId, jobs) {
        // 抽屉一打开就订阅当前会话的任务流：会话页上的入口要如实显示「几个还在跑」，
        // 只在切到任务页才订阅的话，那个数字在入口上永远是 0，等于没有。
        // 收了帧也不会泄漏——抽屉关掉由下面的 DisposableEffect 释放。
        if (jobsSessionId != null) jobs?.watch(jobsSessionId)
    }
    // 当前会话没了（被删/被切主机），任务页失去上下文，退回会话页。
    LaunchedEffect(jobsSessionId) {
        if (jobsSessionId == null) pane = DrawerPane.Sessions
    }
    // 抽屉关掉（或被切走）才释放：任务流是实时订阅，留着会一直收帧。
    DisposableEffect(jobs, jobsSessionId) {
        onDispose { if (jobsSessionId != null) jobs?.release(jobsSessionId) }
    }
    val liveJobs = jobsState?.jobs?.count { !it.terminal } ?: 0

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = if (p.dark) 0.58f else 0.32f))
                .clickable(onClick = onClose)
                .semantics { contentDescription = "关闭会话抽屉"; role = Role.Button },
        )
        Column(
            Modifier.fillMaxHeight()
                .fillMaxWidth(0.88f)
                .floatShadow(p, RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp))
                .clip(RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp))
                .background(Brush.verticalGradient(listOf(p.chatTop, p.chatMid, p.chatBottom)))
                // 抽屉是盖在整屏之上的浮层，自身不带 statusBarsPadding，标题会顶到状态栏
                // 上和系统时钟/信号图标叠字（实机截图可见）。底色仍是满幅，只把内容下移。
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 12.dp, top = 18.dp, bottom = 18.dp),
        ) {
            // 这里原本有一层标题行（设备名 + 副标题「远程会话」+ 关闭钮）。已连接时
            // 标题就是 host.name、副标题又写一遍「远程会话」，零信息增量；而「正在连接」
            // 「未连接」这两种状态下面的设备卡片已经各自表达了。所以整行去掉，
            // 关闭钮并到「我的电脑」那一行右端，抽屉的第一行就是电脑切换。
            HostSection(
                hosts = hosts,
                connectedHostId = connectedHostId,
                connectingHostId = connectingHostId,
                hostsLoading = hostsLoading,
                hostsError = hostsError,
                connectionError = connectionError,
                expanded = hostsExpanded,
                onToggle = { hostsExpanded = !hostsExpanded },
                onRefresh = onRefreshHosts,
                onSelect = onSelectHost,
                onRetry = onRetryHost,
                onOpenSettings = onOpenSettings,
                onOpenDetail = onOpenHostDetail,
                onClose = onClose,
            )
            Spacer(Modifier.height(14.dp))

            when (pane) {
                DrawerPane.Jobs -> {
                    JobsPaneHeader(
                        sessionTitle = jobsSessionTitle,
                        onBack = { pane = DrawerPane.Sessions },
                    )
                    Spacer(Modifier.height(10.dp))
                    JobsTab(
                        jobs = jobs,
                        jobsState = jobsState,
                        jobsSessionId = jobsSessionId,
                        switching = switching,
                        onOpenJob = { jobId -> jobs?.openJob(jobId) },
                        onCloseJob = { jobs?.closeJob() },
                        onKill = { jobId -> pendingKill = jobsState?.jobs?.firstOrNull { it.id == jobId } },
                        onRetryList = { jobs?.retryList() },
                        onRetryFollow = { jobs?.retryFollow() },
                    )
                }

                DrawerPane.Sessions -> SessionsTab(
                    hostKey = connectedHostId,
                    connected = connectedHostId != null,
                    switching = switching,
                    sessions = sessions,
                    loading = sessionsLoading,
                    error = sessionsError,
                    currentSessionId = currentSessionId,
                    onRefresh = onRefreshSessions,
                    onSelect = onSelectSession,
                    onNew = onNewSession,
                    onOpenAll = onOpenAllSessions,
                    jobsSessionTitle = jobsSessionTitle,
                    liveJobs = liveJobs,
                    jobsKnown = jobsState != null,
                    onOpenJobs = { pane = DrawerPane.Jobs },
                )
            }
        }
    }

    // 停止任务是不可撤销的：先确认，再发 job/kill。任务已自己结束时不再弹。
    val killTarget = pendingKill
    if (killTarget != null) {
        val terminalNow = jobsState?.jobs?.firstOrNull { it.id == killTarget.id }?.terminal == true
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingKill = null },
            title = { Text(if (terminalNow) "这个任务已经结束" else "停止这个任务？") },
            text = {
                Text(
                    if (terminalNow) "电脑已经把它收尾了，不需要再停。"
                    else "会向电脑发送停止请求，正在跑的命令会被中断。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                if (!terminalNow) {
                    Text(
                        "停止",
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .clickable {
                                jobs?.kill(killTarget.id)
                                pendingKill = null
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.danger,
                    )
                }
            },
            dismissButton = {
                Text(
                    "取消",
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .clickable { pendingKill = null }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.textSecondary,
                )
            },
        )
    }
}

/* ------------------------------------------------------------------ 我的电脑 */

/**
 * 抽屉顶部的电脑切换区。
 *
 * 当前主机常驻一行，展开后才列全部电脑——抽屉纵向空间要留给会话列表，而大多
 * 数人平时只有一两台机器。连接中/失败时会自动展开，避免「点了没反应」。
 *
 * 顶部的关闭钮也挂在这一行：抽屉没有独立标题栏了，「我的电脑」就是视觉上的第一行，
 * 关闭钮放它右端既省一层高度，又仍在拇指够得到的右上角。
 */
@Composable
private fun HostSection(
    hosts: List<RemoteHost>,
    connectedHostId: String?,
    connectingHostId: String?,
    hostsLoading: Boolean,
    hostsError: String?,
    connectionError: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (RemoteHost) -> Unit,
    onRetry: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onOpenDetail: (RemoteHost) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    val current = hosts.firstOrNull { it.id == connectedHostId }
    val connecting = hosts.firstOrNull { it.id == connectingHostId }
    val busy = connectingHostId != null

    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "我的电脑",
                modifier = Modifier.weight(1f),
                // 抽屉的第一行了，不能再用 labelMedium 那种小标签的分量。
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = p.textSecondary,
            )
            if (hostsLoading) {
                TypingDots(color = p.textTertiary)
                Spacer(Modifier.width(Space.xs))
            }
            CircleButton(size = 30.dp, onClick = onOpenSettings, label = "设置") {
                GlyphTune(size = 16.dp, tint = p.textSecondary)
            }
            Spacer(Modifier.width(Space.xs))
            CircleButton(size = 30.dp, onClick = onRefresh, label = "刷新电脑列表") {
                GlyphRefresh(size = 16.dp, tint = if (hostsLoading) p.textTertiary else p.textSecondary)
            }
            Spacer(Modifier.width(Space.xs))
            CircleButton(size = 36.dp, onClick = onClose, label = "关闭抽屉") {
                Text("×", style = MaterialTheme.typography.titleLarge, color = p.textSecondary)
            }
        }

        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(p.float)
                .clickable(onClick = onToggle)
                .semantics { contentDescription = "切换电脑"; role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(online = connecting != null || current != null || hosts.any { it.online })
            Spacer(Modifier.width(Space.s))
            Column(Modifier.weight(1f)) {
                Text(
                    connecting?.name ?: current?.name ?: "选择一台电脑",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = p.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        connecting != null -> "连接中…"
                        current != null -> "当前连接 · 在线"
                        hosts.any { it.online } -> "${hosts.count { it.online }} 台在线"
                        hosts.isEmpty() -> "还没有电脑"
                        else -> "全部离线"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connecting != null || current != null) p.success else p.textTertiary,
                )
            }
            if (current != null && !busy) {
                Pill("当前", p.success)
                Spacer(Modifier.width(Space.s))
            }
            Box(Modifier.rotate(if (expanded) 270f else 90f)) {
                GlyphChevron(size = 15.dp, tint = p.textTertiary)
            }
        }

        if (expanded) {
            if (hosts.isEmpty() && hostsError == null) {
                Text(
                    "还没有电脑。在电脑上登录同一个账号并打开「远程访问」，它会出现在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 208.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.float.copy(alpha = 0.72f))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    hosts.forEach { host ->
                        HostRow(
                            host = host,
                            current = host.id == connectedHostId,
                            connecting = host.id == connectingHostId,
                            enabled = !busy && host.online && host.id != connectedHostId,
                            onClick = { onSelect(host) },
                            onOpenDetail = { onOpenDetail(host) },
                        )
                    }
                }
            }
        }

        hostsError?.let { InlineError(it, retryLabel = "重试", onRetry = onRefresh) }
        if (connectionError != null) {
            InlineError(
                connectionError,
                retryLabel = if (onRetry != null && !busy) "重新连接" else null,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun HostRow(
    host: RemoteHost,
    current: Boolean,
    connecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onOpenDetail: (() -> Unit)?,
) {
    val p = LocalRuyiPalette.current
    val active = current || connecting
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) p.accentSoft else Color.Transparent)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics {
                    contentDescription = when {
                        connecting -> "正在连接 ${host.name}"
                        current -> "${host.name}，当前连接"
                        else -> "切换到 ${host.name}"
                    }
                    role = Role.Button
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(online = host.online || active, size = 7.dp)
            Spacer(Modifier.width(Space.s))
            Text(
                host.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    active -> p.onAccentSoft
                    host.online -> p.text
                    else -> p.textTertiary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    connecting -> "连接中…"
                    current -> "当前"
                    host.online -> "在线"
                    else -> "离线"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    connecting || current -> p.success
                    host.online -> p.success
                    else -> p.textTertiary
                },
            )
        }
        if (onOpenDetail != null) {
            Spacer(Modifier.width(Space.xs))
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable { onOpenDetail() }
                    .semantics { contentDescription = "查看 ${host.name} 详情"; role = Role.Button }
                    .padding(Space.xs),
            ) {
                GlyphChevron(size = 14.dp, tint = p.textTertiary)
            }
        }
    }
}

/* ------------------------------------------------------------------ 会话 */

@Composable
private fun ColumnScope.SessionsTab(
    hostKey: String?,
    connected: Boolean,
    switching: Boolean,
    sessions: List<SessionSummary>,
    loading: Boolean,
    error: String?,
    currentSessionId: String?,
    onRefresh: () -> Unit,
    onSelect: (SessionSummary) -> Unit,
    onNew: () -> Unit,
    onOpenAll: (() -> Unit)?,
    jobsSessionTitle: String?,
    liveJobs: Int,
    jobsKnown: Boolean,
    onOpenJobs: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    // 只在「这台电脑的列表第一次到位」时静默定位到第一项；之后的定时刷新不碰滚动位置，
    // 让用户自己决定读到哪。列表按 updatedAt 倒序，新会话自然排顶部，所以停在首项就是
    // 停在最新一条。切电脑会换 controller、列表重新变空再填满，用 anchoredHost 区分
    // 「首屏定位」和「同机刷新」。
    val listState = remember { LazyListState() }
    var anchoredHost by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hostKey, sessions.isEmpty()) {
        if (hostKey == null || sessions.isEmpty()) return@LaunchedEffect
        if (anchoredHost != hostKey) {
            anchoredHost = hostKey
            listState.scrollToItem(0)
        }
    }

    if (switching) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            TypingDots(color = p.textTertiary)
            Text("正在切换电脑，旧会话列表已隐藏…", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }
        return
    }

    if (!connected) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text("还没有连接到电脑。", style = MaterialTheme.typography.bodyMedium, color = p.text)
            Text(
                "点上方「我的电脑」选一台在线设备；连上后这里会列出它的会话。",
                style = MaterialTheme.typography.bodySmall,
                color = p.textSecondary,
            )
        }
        return
    }

    // 后台任务的入口。以前它是和「会话」并列的页签，但任务按会话执行、没选会话时整页
    // 是空的，并列关系会误导。收成一行入口后：有活任务给数字，没有就明说，且只有在
    // 真的选中了会话时才出现。
    if (jobsSessionTitle != null) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(p.float)
                .clickable(onClick = onOpenJobs)
                .semantics { contentDescription = "查看当前会话的后台任务"; role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "后台任务",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = p.text,
                    maxLines = 1,
                )
                Text(
                    "当前会话 · $jobsSessionTitle",
                    style = MaterialTheme.typography.labelSmall,
                    color = p.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                liveJobs > 0 -> Pill("$liveJobs 个运行中", p.success)
                jobsKnown -> Text("无进行中", style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
                // 还没拿到状态：不写「无进行中」，那会与事实相反。
                else -> TypingDots(color = p.textTertiary)
            }
            Spacer(Modifier.width(Space.s))
            Box(Modifier.rotate(90f)) { GlyphChevron(size = 15.dp, tint = p.textTertiary) }
        }
        Spacer(Modifier.height(8.dp))
    }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(p.float)
            .clickable(enabled = !loading, onClick = onNew)
            .semantics { contentDescription = "新建会话"; role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphPlus(size = 19.dp, tint = p.accent)
        Spacer(Modifier.width(10.dp))
        Text(
            "新建会话",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = p.text,
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "最近会话",
            style = MaterialTheme.typography.labelMedium,
            color = p.textTertiary,
            modifier = Modifier.weight(1f),
        )
        if (loading) TypingDots(color = p.textTertiary)
        else Text("${sessions.size} 条", style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
        Spacer(Modifier.width(Space.xs))
        CircleButton(size = 28.dp, onClick = onRefresh, label = "刷新会话列表") {
            GlyphRefresh(size = 15.dp, tint = if (loading) p.textTertiary else p.textSecondary)
        }
    }
    Spacer(Modifier.height(6.dp))
    error?.let { InlineError(it, retryLabel = "重新读取", onRetry = onRefresh) }

    when {
        loading && sessions.isEmpty() -> Column {
            repeat(3) { SkeletonRow() }
        }

        sessions.isEmpty() -> Column(
            Modifier.fillMaxWidth().padding(vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text("这台电脑还没有会话。", style = MaterialTheme.typography.bodyMedium, color = p.text)
            Text("点上面的「新建会话」，或直接在电脑上继续原来的会话。", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }

        else -> LazyColumn(
            Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(sessions.sortedByDescending { it.updatedAt }, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == currentSessionId,
                    onClick = { onSelect(session) },
                )
            }
        }
    }
    onOpenAll?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            "查看全部会话 →",
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .clickable(onClick = it).padding(horizontal = 12.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
        )
    }
}

@Composable
private fun SessionRow(session: SessionSummary, selected: Boolean, onClick: () -> Unit) {
    val p = LocalRuyiPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) p.bubbleUserSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "打开 ${session.displayTitle}"; role = Role.Button }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (session.running) {
            Box(Modifier.size(7.dp).background(p.success, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                session.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = p.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    session.running -> "运行中 · ${formatWhen(session.updatedAt)}"
                    session.blank -> "空会话"
                    else -> formatWhen(session.updatedAt)
                },
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ------------------------------------------------------------------ 后台任务 */

/**
 * 后台任务面板。
 *
 * 任务流本身是 `job/list(sessionId)`，所以「这是哪个会话的任务」必须一眼可辨——这句
 * 由上一层的 [JobsPaneHeader] 负责，这里不再重复一遍（重复过一版，标题下紧跟着又写了
 * 一次「当前会话 · X」，两行同样的字）。没有选中会话时只给引导，不展示任何跨会话
 * 聚合——那会让人误以为任务是电脑级的。
 */
@Composable
private fun JobsTab(
    jobs: RemoteJobsController?,
    jobsState: RemoteJobsState?,
    jobsSessionId: String?,
    switching: Boolean,
    onOpenJob: (String) -> Unit,
    onCloseJob: () -> Unit,
    onKill: (String) -> Unit,
    onRetryList: () -> Unit,
    onRetryFollow: () -> Unit,
) {
    val p = LocalRuyiPalette.current
    when {
        switching -> Text(
            "正在切换电脑，任务列表已隐藏。",
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
        )

        jobsSessionId == null -> Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("先选择一个会话。", style = MaterialTheme.typography.bodyMedium, color = p.text)
            Text(
                "后台任务按会话执行；打开某条会话后，这里会显示它正在跑的任务。",
                style = MaterialTheme.typography.bodySmall,
                color = p.textSecondary,
            )
        }

        jobsState == null || jobs == null -> Text(
            "连接断开，读不到这台电脑的后台任务。",
            style = MaterialTheme.typography.bodySmall,
            color = p.textSecondary,
        )

        else -> RemoteJobsPanel(
            state = jobsState,
            onOpenJob = onOpenJob,
            onCloseJob = onCloseJob,
            onKill = onKill,
            onRetryList = onRetryList,
            onRetryFollow = onRetryFollow,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/* ------------------------------------------------------------------ 小部件 */

/**
 * 任务面板顶部的返回路径 + 这是哪个会话的任务。
 *
 * 后台任务不再是一个并列页签，进到这一页就没有「页签」可点回去了，必须显式给返回，
 * 否则用户只能关掉整个抽屉再重新进来。
 */
@Composable
private fun JobsPaneHeader(sessionTitle: String?, onBack: () -> Unit) {
    val p = LocalRuyiPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleButton(size = 32.dp, onClick = onBack, label = "返回会话列表") {
            // chevron 默认朝右，转 180° 朝左。
            Box(Modifier.rotate(180f)) { GlyphChevron(size = 16.dp, tint = p.textSecondary) }
        }
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f)) {
            Text(
                "后台任务",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = p.text,
                maxLines = 1,
            )
            Text(
                if (sessionTitle != null) "当前会话 · $sessionTitle" else "当前会话",
                style = MaterialTheme.typography.labelSmall,
                color = p.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CircleButton(
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    label: String,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 抽屉是浮层，阴影要比卡片实一点；必须在裁剪之前画，否则会被自己裁掉。 */
private fun Modifier.floatShadow(p: RuyiPalette, shape: Shape) =
    shadow(
        elevation = 12.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = if (p.dark) 0.5f else 0.08f),
        spotColor = Color.Black.copy(alpha = if (p.dark) 0.5f else 0.14f),
    )
