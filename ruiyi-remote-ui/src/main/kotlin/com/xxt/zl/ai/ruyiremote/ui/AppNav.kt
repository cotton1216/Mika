package com.xxt.zl.ai.ruyiremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xxt.zl.ai.ruyiremote.account.HostConnection
import com.xxt.zl.ai.ruyiremote.account.RemoteHost
import com.xxt.zl.ai.ruyiremote.channel.HarnessTunnel
import com.xxt.zl.ai.ruyiremote.session.ChatState
import com.xxt.zl.ai.ruyiremote.session.RemoteJobsController
import com.xxt.zl.ai.ruyiremote.session.SessionApi
import com.xxt.zl.ai.ruyiremote.session.SessionChatController
import com.xxt.zl.ai.ruyiremote.session.SessionSnapshotStore
import com.xxt.zl.ai.ruyiremote.session.SessionSummary
import com.xxt.zl.ai.ruyiremote.session.SharedPrefsSessionSnapshotStore
import com.xxt.zl.ai.ruyiremote.session.sessionSnapshotNamespace
import com.xxt.zl.ai.ruyiremote.ui.components.GlyphMonitor
import com.xxt.zl.ai.ruyiremote.ui.screen.AlternateSignInScreen
import com.xxt.zl.ai.ruyiremote.ui.screen.ChatScreen
import com.xxt.zl.ai.ruyiremote.ui.screen.ConnectScreen
import com.xxt.zl.ai.ruyiremote.ui.screen.HomeScreen
import com.xxt.zl.ai.ruyiremote.ui.screen.HostDetailScreen
import com.xxt.zl.ai.ruyiremote.ui.screen.HostWorkspaceScreen
import com.xxt.zl.ai.ruyiremote.ui.screen.HostsScreen
import com.xxt.zl.ai.ruyiremote.ui.screen.SessionDrawer
import com.xxt.zl.ai.ruyiremote.ui.screen.SettingsScreen
import com.xxt.zl.ai.ruyiremote.ui.theme.LocalRuyiPalette
import com.xxt.zl.ai.ruyiremote.ui.theme.Space

/** 所有页面统一的系统栏处理：状态栏、导航栏、键盘。 */
fun Modifier.screenFrame(): Modifier = this
    .fillMaxSize()
    .statusBarsPadding()
    .navigationBarsPadding()
    .imePadding()

@Composable
fun DshApp(
    session: RemoteSessionController,
    signIn: SignInController,
    showSso: Boolean = true,
    /**
     * 宿主（如愿）嵌入时传入：远程控制是宿主里的一页，根页要能退回去。
     * 独立 App 时为 null —— 它自己就是根，返回键交给系统退出。
     */
    onExit: (() -> Unit)? = null,
    /**
     * 宿主用底部 Tab 切换时，本页常驻组合以保留连接；Tab 未选中时传 false，
     * 禁用这里的返回键处理，把返回键交回宿主。独立 App 或常显时保持默认 true。
     * 由 false 变 true（切回本 Tab）时会把导航栈清回根页，见下方 wasActive。
     */
    active: Boolean = true,
) {
    val p = LocalRuyiPalette.current
    val signedIn = session.phase is AccountPhase.SignedIn

    // 只存「在根页面之上压了几层」，根页面由登录态推导。这样登录/登出不需要维护
    // 两个列表的一致性：登出时清空栈，根自然变回 Connect。
    val stack = remember { mutableStateListOf<Screen>() }
    var forward by remember { mutableStateOf(true) }
    // 抽屉是全局的：切主机后要先落到新主机的工作区，但抽屉本身不能跟着销毁。
    var drawerOpen by remember { mutableStateOf(false) }
    // 失败重试要连的是「上一次尝试的那台」；连接状态本身不记 hostId。
    var lastSwitchTarget by remember { mutableStateOf<RemoteHost?>(null) }

    // 切回如意 Tab（active 由 false 变 true）时回到根页（会话列表），不恢复上次停留的
    // 会话详情/设置。Tab 切换是「回到这个 Tab 的首页」；连接不断，只重置导航栈。
    var wasActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !wasActive) {
            forward = false
            stack.clear()
            drawerOpen = false
        }
        wasActive = active
    }

    val push: (Screen) -> Unit = { forward = true; stack.add(it) }
    val pop: () -> Unit = {
        forward = false
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    val connection = session.connection
    val connectingHostId = session.connectingHostId
    val switching = connectingHostId != null
    // 会话列表快照：只缓存元数据（标题/时间/路径），正文不落盘；按中继 + 主机隔离，
    // 否则同一中继下切电脑会把上一台的会话标题先显示出来。
    val context = LocalContext.current
    val snapshotStore = remember { SharedPrefsSessionSnapshotStore(context) }
    val hostId = session.connectedHost?.id
    val snapshotNamespace = remember(session.serverUrl, hostId) {
        sessionSnapshotNamespace(session.serverUrl, hostId)
    }
    val chat = rememberChatController(connection, snapshotStore, snapshotNamespace)
    val jobs = rememberJobsController(connection)
    val chatState = rememberChatState(chat)
    val currentChat = (stack.lastOrNull() as? Screen.Chat)

    /**
     * 打开某条会话。
     *
     * 主机 id 只在「当前真的连着」时取：切主机过程中旧控制器还活着，但它的
     * session 不属于新主机，绝不能借此把旧会话重新打开。
     */
    val openChat: (SessionSummary) -> Unit = { summary ->
        val hostId = session.connectedHost?.id
        if (hostId != null) {
            forward = true
            drawerOpen = false
            if (stack.lastOrNull() is Screen.Chat) {
                stack[stack.lastIndex] = Screen.Chat(hostId, summary)
            } else {
                stack.add(Screen.Chat(hostId, summary))
            }
        }
    }

    /**
     * 抽屉里点了一台电脑。
     *
     * 点当前这台只关抽屉；换主机则先丢掉整个旧栈、落到新主机的工作区，再发起
     * 连接。导航先于连接发生，旧 ChatScreen 会在同一帧被销毁，从而不会出现
     * 「新连接把旧主机 sessionId 又打开一次」。失败时仍停在工作区，抽屉保持
     * 展开，可直接重试或改选另一台。
     */
    val switchHost: (RemoteHost) -> Unit = { host ->
        if (switching) {
            // 重入直接忽略：中继只允许一条链路，重复点击只会互相替换。
        } else if (host.id == session.connectedHost?.id && connection != null) {
            drawerOpen = false
        } else {
            lastSwitchTarget = host
            forward = true
            stack.clear()
            stack.addAll(stackAfterHostSwitch(host.id))
            drawerOpen = true
            session.connect(host)
        }
    }

    // 只在「登录态真的翻转」时清栈。若写成 `if (!signedIn) stack.clear()`，在二级
    // 登录页上输错密码（phase 变成 Failed）也会被弹回首屏，错误提示跟着跑错地方。
    var wasSignedIn by remember { mutableStateOf(signedIn) }
    LaunchedEffect(signedIn) {
        if (wasSignedIn != signedIn) {
            forward = !signedIn
            stack.clear()
            drawerOpen = false
            lastSwitchTarget = null
            wasSignedIn = signedIn
        }
    }

    // 掉线：Chat 已经没有数据源，退回上一层。Workspace 保留：它能在断开态显示
    // 重试与主机入口，比把用户直接弹回电脑列表更接近他刚才的位置。
    LaunchedEffect(connection, connectingHostId) {
        if (connection == null && connectingHostId == null) {
            while (stack.isNotEmpty() && stack.last() is Screen.Chat) {
                stack.removeAt(stack.lastIndex)
            }
        }
    }

    val root: Screen = if (signedIn) Screen.Home else Screen.Connect
    val current = stack.lastOrNull() ?: root

    BackHandler(enabled = active && stack.isNotEmpty()) { pop() }
    // 根页（我的电脑 / 连接电脑）没有内部可退的页面：嵌入在宿主里时，退回去的是宿主。
    BackHandler(enabled = active && stack.isEmpty() && onExit != null) { onExit?.invoke() }
    // 抽屉在最上层：返回键先关它，而不是把下面的页面也一起弹走。
    BackHandler(enabled = active && drawerOpen) { drawerOpen = false }

    // 整个应用共用一张「浅蓝纸」：设备列表、会话列表、对话页底色一致，
    // 进出页面时不会觉得换了另一个 App。
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(p.chatTop, p.chatMid, p.chatBottom))),
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                val enter = slideInHorizontally(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) { full -> dir * full / 7 } + fadeIn(tween(220))
                val exit = slideOutHorizontally(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) { full -> -dir * full / 12 } + fadeOut(tween(160))
                enter togetherWith exit
            },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.Connect -> ConnectScreen(
                    session = session,
                    signIn = signIn,
                    onOpenAlternatives = { push(Screen.AltSignIn) },
                )

                Screen.AltSignIn -> AlternateSignInScreen(
                    session = session,
                    signIn = signIn,
                    onBack = pop,
                    showSso = showSso,
                )

                Screen.Home -> HomeScreen(
                    session = session,
                    chat = if (connection != null) chat else null,
                    onOpenHostList = { drawerOpen = true },
                    onSelectSession = openChat,
                )

                Screen.Hosts -> HostsScreen(
                    session = session,
                    onOpenHost = { push(Screen.HostDetail(it.id)) },
                    onOpenSettings = { push(Screen.Settings) },
                    onExit = onExit,
                )

                is Screen.HostDetail -> {
                    val host = session.hosts.firstOrNull { it.id == screen.hostId }
                    if (host == null) {
                        // 刷新后这台电脑从列表里消失了：直接退回，不要渲染半截详情。
                        LaunchedEffect(screen.hostId) { pop() }
                    } else {
                        HostDetailScreen(
                            session = session,
                            host = host,
                            onBack = pop,
                            onOpenSessions = { push(Screen.Workspace(host.id)) },
                        )
                    }
                }

                is Screen.Workspace -> {
                    val host = session.hosts.firstOrNull { it.id == screen.hostId }
                        ?: session.connectedHost?.takeIf { it.id == screen.hostId }
                        ?: lastSwitchTarget?.takeIf { it.id == screen.hostId }
                    val connected = session.connectedHost?.id == screen.hostId && connection != null
                    HostWorkspaceScreen(
                        hostName = host?.name ?: "这台电脑",
                        hostOnline = host?.online == true,
                        connected = connected,
                        connecting = connectingHostId == screen.hostId,
                        switching = switching,
                        transportNotice = session.transportNotice,
                        chat = if (connected) chat else null,
                        onOpenDrawer = { drawerOpen = true },
                        onRetryConnect = retryConnectFor(
                            hostId = screen.hostId,
                            target = lastSwitchTarget,
                            connecting = switching,
                            connect = session::connect,
                        ),
                        onSelectSession = { summary -> push(Screen.Chat(screen.hostId, summary)) },
                    )
                }

                is Screen.Chat -> ChatScreen(
                    // 只把「属于这条会话所在主机」的控制器交出去；切换期间旧控制器
                    // 即便还活着，也不能拿来渲染这条会话。
                    chat = chat.takeIf { session.connectedHost?.id == screen.hostId },
                    summary = screen.summary,
                    uploader = remember(connection, screen.hostId) {
                        connection
                            ?.takeIf { session.connectedHost?.id == screen.hostId }
                            ?.let { c ->
                                com.xxt.zl.ai.ruyiremote.session.WorkspaceUploader(c.capabilities) { method, params ->
                                    c.rpc.call(method, params)
                                }
                            }
                    },
                    reconnecting = session.reconnecting,
                    // 顶栏副标题要显示「哪台电脑 · 远程」，设备名从当前主机取；
                    // 主机列表刷新后短暂为空时退回「这台电脑」，不要让标题闪成空白。
                    hostName = session.hosts.firstOrNull { it.id == screen.hostId }?.name
                        ?: session.connectedHost?.takeIf { it.id == screen.hostId }?.name
                        ?: "这台电脑",
                    onOpenDrawer = { drawerOpen = true },
                )

                Screen.Settings -> SettingsScreen(session = session, onBack = pop)
            }
        }

        if (drawerOpen && signedIn) {
            val visibleChat = current as? Screen.Chat
            // 切换中一律不给旧会话列表：主机已经变了，旧数据现在属于另一台电脑。
            val sessions = if (switching) emptyList() else chatState?.sessions.orEmpty()
            SessionDrawer(
                hosts = session.hosts,
                connectedHostId = session.connectedHost?.id,
                connectingHostId = connectingHostId,
                hostsLoading = session.hostsLoading,
                hostsError = session.hostsError,
                connectionError = session.connectionError,
                onRetryHost = retryConnectFor(
                    hostId = lastSwitchTarget?.id,
                    target = lastSwitchTarget,
                    connecting = switching,
                    connect = session::connect,
                ),
                onRefreshHosts = session::refreshHosts,
                onSelectHost = switchHost,
                onOpenSettings = { drawerOpen = false; push(Screen.Settings) },
                onOpenHostDetail = { host -> drawerOpen = false; push(Screen.HostDetail(host.id)) },
                sessions = sessions,
                sessionsLoading = switching || chatState?.sessionsLoading == true,
                sessionsError = if (switching) null else chatState?.sessionsError,
                switching = switching,
                currentSessionId = visibleChat?.summary?.id,
                onRefreshSessions = { if (!switching) chat?.loadSessions() },
                onSelectSession = openChat,
                onNewSession = {
                    if (!switching) chat?.createSession(onOpened = openChat)
                },
                jobs = if (switching) null else jobs,
                // 后台任务只有会话级：未选会话时抽屉会引导先选会话，不拿主机级聚合冒充。
                jobsSessionId = visibleChat?.summary?.id,
                jobsSessionTitle = visibleChat?.summary?.displayTitle,
                onOpenAllSessions = visibleChat?.let {
                    {
                        drawerOpen = false
                        push(Screen.Workspace(it.hostId))
                    }
                },
                onClose = { drawerOpen = false },
            )
        }

        // 冷启动恢复登录态期间盖一层品牌页。
        //
        // 不加这层时首帧就是配对码：凭据还没读回来，phase 仍是 SignedOut，已登录
        // 用户会看到自己的登录页闪一下，再跳回电脑列表。恢复本身很快，但低端机
        // 冷启动可达十几秒（本机模拟器实测 12s），这段窗口非常显眼。
        AnimatedVisibility(
            visible = session.booting,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(260)),
        ) {
            BootSplash(Modifier.fillMaxSize().background(p.bg))
        }
    }
}

/** 只有「目标主机明确、当前没有连接尝试」时才提供重试入口。 */
private fun retryConnectFor(
    hostId: String?,
    target: RemoteHost?,
    connecting: Boolean,
    connect: (RemoteHost) -> Unit,
): (() -> Unit)? {
    if (connecting || hostId == null) return null
    val host = target?.takeIf { it.id == hostId } ?: return null
    return { connect(host) }
}

@Composable
private fun BootSplash(modifier: Modifier = Modifier) {
    val p = LocalRuyiPalette.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(p.accent),
            contentAlignment = Alignment.Center,
        ) {
            GlyphMonitor(size = 30.dp, tint = p.onAccent)
        }
        Spacer(Modifier.height(Space.xl))
        Text("如意远程", style = MaterialTheme.typography.titleLarge, color = p.text)
        Spacer(Modifier.height(Space.l))
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = p.textTertiary,
        )
    }
}

/**
 * 连接建立后才存在的东西：一条隧道的控制器。
 *
 * 挂在 App 层而不是聊天页里，是因为「会话列表」和「对话」是两屏，控制器若随页面
 * 销毁，从列表进对话就会重复订阅 follow —— 每进一次多一条流，Host 那边看到的是
 * 反复重连。这里随着 connection 生命周期走，进出页面只是读同一份状态。
 */
@Composable
private fun rememberChatController(
    connection: HostConnection?,
    snapshotStore: SessionSnapshotStore?,
    snapshotNamespace: String?,
): SessionChatController? {
    val scope = rememberCoroutineScope()
    // 控制器不随 connection 对象变化而重建：重连是「换底层隧道」，不是「重建会话层」。
    // 若仍用 remember(connection)，重连成功后控制器重建，已加载的聊天记录会被整页
    // 清空再重拉。这里只持有同一份控制器，重连时调 onTransportReconnected 无缝续传。
    val holder = remember { mutableStateOf<SessionChatController?>(null) }

    LaunchedEffect(connection) {
        val c = connection
        val current = holder.value
        if (c == null) {
            // 断开：销毁控制器。
            current?.stop()
            holder.value = null
        } else if (current == null) {
            // 首次连上：创建控制器并启动。
            val created = SessionChatController(
                SessionApi(HarnessTunnel(c.rpc, c.capabilities)),
                scope,
                snapshotStore,
                snapshotNamespace,
            )
            holder.value = created
            created.start()
        } else {
            // 重连：切换底层隧道，会话层续传。
            current.onTransportReconnected(HarnessTunnel(c.rpc, c.capabilities))
        }
    }

    // 切电脑只换快照键，不重建控制器：聊天记录必须连续，也不能让上一台电脑的
    // 骨架屏串过来。独立 effect，避免被误当成 transport 变化而触发重连。
    LaunchedEffect(snapshotNamespace) {
        holder.value?.updateSnapshotNamespace(snapshotNamespace)
    }

    // 应用退出时兜底清理（正常断开由上面的 c == null 分支处理）。
    DisposableEffect(Unit) {
        onDispose { holder.value?.stop() }
    }
    return holder.value
}

/** 抽屉和下拉列表都读同一份会话状态；没有连接时为 null。 */
@Composable
private fun rememberChatState(chat: SessionChatController?): ChatState? =
    chat?.state?.collectAsState()?.value

/**
 * 后台任务控制器。
 *
 * 和会话控制器一样挂在 App 层：抽屉开关只决定「要不要建立流」，不决定控制器
 * 的生死。否则每开一次抽屉就新建一条 `job/list`，桌面端那边看到的是反复重连。
 */
@Composable
private fun rememberJobsController(connection: HostConnection?): RemoteJobsController? {
    val scope = rememberCoroutineScope()
    val controller = remember(connection) {
        connection?.let { c ->
            RemoteJobsController(HarnessTunnel(c.rpc, c.capabilities), scope)
        }
    }
    DisposableEffect(controller) { onDispose { controller?.stop() } }
    return controller
}
