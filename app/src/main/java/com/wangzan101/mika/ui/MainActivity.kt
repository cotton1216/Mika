package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.AppLanguage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.memory.MemoryRuntime
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.cap.web.WebHandover
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.browser.UserBrowser
import com.xxt.zl.ai.ruyiremote.links.DeepLinks
import com.xxt.zl.ai.ruyiremote.ui.RemoteHome
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.about.DiagnosticsCollector
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftImporter
import com.wangzan101.mika.chat.SharedDraftStore
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.SystemStatusMonitor
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingNotifier
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.ui.theme.appearanceDark
import com.wangzan101.mika.ui.theme.systemBarsFor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var monitor: SystemStatusMonitor
    private var requestedConversation by mutableStateOf<String?>(null)
    private var requestedCalls by mutableStateOf(false)
    private var requestedCallHistoryId by mutableStateOf<String?>(null)
    private var openTeach by mutableStateOf(false)
    /** 如意远程 SSO 回跳 URI:右侧 RemoteHome 订阅后消费。 */
    private var pendingRemoteCallback by mutableStateOf<String?>(null)
    private var requestedSharedDraft by mutableStateOf<SharedDraftRequest?>(null)
    private var shareJob: Job? = null
    private var shareSerial = 0L
    private var shareInCurrent = false
    private var shareHandled = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // 用户选过「不再询问」时系统不再弹窗,只能去设置里打开
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
        monitor.refresh()
    }

    // 安卓 12 及以前套上 App 里选的语言;13 起系统自己套
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 如意登录:浏览器授权后跳 ruyiai://auth2/callback 唤起;冷启动时在这里收
        RuyiSignIn.onScheme(intent.data)
        if (savedInstanceState == null) {
            (application as MikaApp).chat.cancelPendingSharedDraft()
        }
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
        requestedCalls = intent.getBooleanExtra(EXTRA_OPEN_CALLS, false)
        requestedCallHistoryId = intent.getStringExtra(EXTRA_CALL_HISTORY_ID)
        takeTeach(intent)
        // 如意登录回跳可能冷启动带进来;DeepLinks 已在 inside 排队,RemoteHome 订阅时补发。
        DeepLinks.takeLaunch(intent, DeepLinks.isConsumed(intent))
        shareInCurrent = savedInstanceState?.getBoolean(STATE_SHARE_IN_CURRENT, false) ?: false
        shareHandled = savedInstanceState?.getBoolean(STATE_SHARE_HANDLED, false) ?: false
        // 已处理完的分享在旋转/重建时不重复触发;真正的冷启动分享或尚未完成的后台分享继续收下。
        if (!shareHandled && (savedInstanceState == null || intent.action == Intent.ACTION_SEND)) {
            receiveShare(intent, inCurrentConversation = shareInCurrent)
        }
        monitor = SystemStatusMonitor(this, PhoneServices.of(this).shellControl)
        val store = ProviderStore.get(this)
        setContent {
            // 外观按设置来,改了当场换;系统栏图标的深浅跟着界面
            val dark = appearanceDark()
            LaunchedEffect(dark) { systemBarsFor(dark) }
            MikaTheme(dark = dark, reduceMotion = animationsOff(this)) {
                // 在输入框外面点一下或拖动就收起键盘,全 App 统一
                KeyboardDismissArea(Modifier.fillMaxSize()) {
                    MikaRoot(
                        store, monitor, ::perform, requestedConversation, { requestedConversation = null },
                        requestedCalls, requestedCallHistoryId, { requestedCalls = false; requestedCallHistoryId = null },
                        requestedSharedDraft,
                        onSharedDraftOpened = { accepted, draft ->
                            requestedSharedDraft = null
                            shareHandled = true
                            // 处理完后换掉 intent,避免后续旋转屏幕再次把这次分享当成新分享或重复弹失败提示。
                            setIntent(Intent(this@MainActivity, MainActivity::class.java))
                            if (!accepted) {
                                (application as MikaApp).sharedDrafts.discard(draft.image)
                                Toast.makeText(this@MainActivity, R.string.share_draft_failed, Toast.LENGTH_SHORT).show()
                            }
                        },
                        openTeach = openTeach,
                        onTeachOpened = { openTeach = false },
                        remoteCallback = pendingRemoteCallback,
                        onRemoteCallbackConsumed = { pendingRemoteCallback = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 如意登录:浏览器授权后跳 ruyiai://auth2/callback 唤起,交给登录流程接着做
        if (RuyiSignIn.onScheme(intent.data)) return
        val app = application as MikaApp
        shareJob?.cancel()
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
        requestedCalls = intent.getBooleanExtra(EXTRA_OPEN_CALLS, false)
        requestedCallHistoryId = intent.getStringExtra(EXTRA_CALL_HISTORY_ID)
        if (requestedConversation != null || intent.getBooleanExtra(EXTRA_OPEN_TEACH, false)) {
            app.chat.cancelPendingSharedDraft()
        }
        takeTeach(intent)
        requestedSharedDraft?.let { app.sharedDrafts.discard(it.draft.image) }
        requestedSharedDraft = null
        // 如意登录回跳:两条路都投一遍(NewIntentListener + 兜底),DeepLinks 内部按 intent 去重。
        DeepLinks.take(intent)
        DeepLinks.deliverFromHost(intent)
        // 界面已在后台存活时收到分享:放入当前打开的会话,而不是新建会话。
        shareInCurrent = true
        shareHandled = false
        receiveShare(intent, inCurrentConversation = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHARE_IN_CURRENT, shareInCurrent)
        outState.putBoolean(STATE_SHARE_HANDLED, shareHandled)
    }

    private fun rejectShare() {
        shareHandled = true
        setIntent(Intent(this, MainActivity::class.java))
        Toast.makeText(this, R.string.share_draft_failed, Toast.LENGTH_SHORT).show()
    }

    private fun receiveShare(intent: Intent, inCurrentConversation: Boolean) {
        if (intent.action != Intent.ACTION_SEND) return
        shareJob?.cancel()
        val type = intent.type.orEmpty()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (text.length > SharedDraftStore.MAX_TEXT_CHARS) {
            rejectShare()
            return
        }
        when {
            type == "text/plain" -> {
                if (text.isBlank()) {
                    rejectShare()
                    return
                }
                requestedSharedDraft = SharedDraftRequest(SharedDraft("", text), ++shareSerial, inCurrentConversation)
            }
            type.startsWith("image/") -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri == null || (intent.clipData?.itemCount ?: 0) > 1) {
                    rejectShare()
                    return
                }
                shareJob = lifecycleScope.launch {
                    val prepared = try {
                        SharedDraftImporter(this@MainActivity, (application as MikaApp).sharedDrafts).image(text, uri, type)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (prepared == null) rejectShare()
                    else requestedSharedDraft = SharedDraftRequest(prepared, ++shareSerial, inCurrentConversation)
                }
            }
            else -> rejectShare()
        }
    }

    private var updateWatch: Job? = null

    override fun onStart() {
        super.onStart()
        // 用户先前点了「完全退出」:打开如愿就回来,胶囊和退出时略过的启动工作在这里补上
        com.wangzan101.mika.quit.MikaQuit.resume(this)
        monitor.start()
        // 每次打开都悄悄查一次新版本(两分钟内连着打开的只查一次),失败不吭声
        val app = application as MikaApp
        val check = app.updates.autoCheckInBackground()
        updateWatch = lifecycleScope.launch {
            check.join()
            app.automaticUpdates.watch(app.updates.state)
        }
        // 电脑连接开着、服务却不在(被系统停过、从后台没能起来):界面在前台时补起来
        (application as MikaApp).mcp.ensureRunning()
    }

    override fun onResume() {
        super.onResume()
        monitor.refresh()
        // 回到前台时按最新设置查一次自动归档(启动时也查过,这里照顾长期不退出的进程)
        (application as MikaApp).autoArchiveNow()
    }

    override fun onStop() {
        updateWatch?.cancel()
        monitor.stop()
        super.onStop()
    }

    override fun onDestroy() {
        shareJob?.cancel()
        // 还没绑到对话的私有副本随这次接收结束清掉;已接受的草稿由 CurrentChat 恢复,不在这里删除。
        requestedSharedDraft?.let { (application as MikaApp).sharedDrafts.discard(it.draft.image) }
        requestedSharedDraft = null
        if (isFinishing) {
            (application as MikaApp).chat.cancelPendingSharedDraft()
        }
        super.onDestroy()
    }

    private val phone get() = PhoneServices.of(this)

    private fun perform(fix: Fix) {
        when (fix) {
            Fix.OpenAccessibility -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Fix.OpenAppInfo -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            Fix.AllowNotifications -> {
                val needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsGrant) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings()
            }
            Fix.OpenAttentionChannel -> openChannelSettings(TaskService.CHANNEL_ATTENTION)
            Fix.OpenResultChannel -> openChannelSettings(TaskService.CHANNEL_RESULT)
            Fix.OpenProgressChannel -> openChannelSettings(TaskService.CHANNEL_PROGRESS)
            Fix.OpenPairingChannel -> openChannelSettings(PairingNotifier.ensureChannel(this).id)
            Fix.AddModel -> Unit // 设置页自己处理
            // 换页由 MikaRoot 处理
            Fix.OpenPhoneSetup, Fix.RepairPhoneHelper -> Unit
            Fix.RetryPhoneHelper -> phone.pairing.scope.launch {
                phone.sharedShell.reconnect()
                phone.shellControl.retry()
            }
            Fix.UnpairPhoneHelper -> phone.pairing.scope.launch { phone.recovery.unpair() }
            Fix.OpenAboutPhone -> openFirst(DeveloperSettings.aboutPhone())
            Fix.OpenDeveloperOptions -> openFirst(DeveloperSettings.developerOptionsPage())
            Fix.OpenUsbDebugging -> openFirst(DeveloperSettings.usbDebuggingPage())
            Fix.OpenWirelessDebugging -> openFirst(DeveloperSettings.wirelessDebuggingPage())
            Fix.OpenWifi -> openFirst(DeveloperSettings.wifiPage())
        }
    }

    private fun appNotificationSettings() =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    private fun openNotificationSettings() = open(appNotificationSettings())

    /** 直接打开某个通知频道的设置页;系统没有这个页面时退回应用通知总页。 */
    private fun openChannelSettings(channelId: String) {
        val channelPage = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        firstThatStarts(listOf(channelPage, appNotificationSettings())) { intent ->
            if (intent.resolveActivity(packageManager) == null) false
            else try { startActivity(intent); true } catch (e: ActivityNotFoundException) { false }
        }
    }

    /** 依次试,第一个能打开的就用:各家设置页不一定都认同一个入口。 */
    private fun openFirst(intents: List<Intent>) {
        val hit = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: intents.last()
        open(hit)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for ${intent.action}", e)
        }
    }

    private fun takeTeach(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_TEACH, false)) return
        openTeach = true
        (application as MikaApp).teach.prefill(intent.getStringExtra(EXTRA_TEACH_GOAL))
        intent.removeExtra(EXTRA_OPEN_TEACH)
    }

    companion object {
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
        const val EXTRA_OPEN_CALLS = "open_calls"
        const val EXTRA_CALL_HISTORY_ID = "call_history_id"
        const val EXTRA_OPEN_TEACH = "open_teach"
        const val EXTRA_TEACH_GOAL = "teach_goal"
        private const val STATE_SHARE_IN_CURRENT = "share_in_current"
        private const val STATE_SHARE_HANDLED = "share_handled"
        private const val TAG = "MikaMain"
    }
}

/** 用系统浏览器打开外部链接(GitHub、X 等);没有浏览器时提示一句。 */
internal fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, AboutCopy.NO_BROWSER, Toast.LENGTH_SHORT).show()
    }
}

/** 依次试 [candidates],第一个 [start] 成功的就停;都不行返回 false。系统设置页各家不一定都有,靠这个退回通用页。 */
internal fun <T> firstThatStarts(candidates: List<T>, start: (T) -> Boolean): Boolean = candidates.any(start)

private data class SharedDraftRequest(val draft: SharedDraft, val serial: Long, val inCurrentConversation: Boolean = false)

/**
 * 如愿 App 的两个顶级模块。
 *
 * [Ruyuan] 是 AI 对话(如愿), [Ruyi] 是远程电脑(如意)。远程控制不再是左侧抽屉里
 * 的一页,而是和对话平级的入口:顶部两个 Tab 切换,「进太深回不去」直接变成点 Tab
 * 切回来,不再依赖返回键或「返回如愿」箭头。
 */
private enum class RootTab { Ruyuan, Ruyi }

/**
 * 页面切换:对话页是根,侧栏和设置等入口把页面压进 [NavStack],返回键和页面上的返回按钮都走 [NavStack.back]。
 * 各页面只通过回调进出,不知道彼此。
 */
@Composable
private fun MikaRoot(
    store: ProviderStore,
    monitor: SystemStatusMonitor,
    activityFix: (Fix) -> Unit,
    requestedConversation: String?,
    onConversationOpened: () -> Unit,
    requestedCalls: Boolean,
    requestedCallHistoryId: String?,
    onCallsOpened: () -> Unit,
    sharedDraft: SharedDraftRequest?,
    onSharedDraftOpened: (Boolean, SharedDraft) -> Unit,
    openTeach: Boolean,
    onTeachOpened: () -> Unit,
    /** 如意远程 SSO 回跳 URI:右侧 RemoteHome 订阅后消费。 */
    remoteCallback: String?,
    onRemoteCallbackConsumed: () -> Unit,
) {
    val context = LocalContext.current
    var focusedCallHistoryId by remember { mutableStateOf<String?>(null) }
    val personalStart = remember { FeatureFlags.get(context).personalStart() }
    val nav = rememberNavStack(if (personalStart && requestedConversation == null) Route.Personal else Route.Chat)
    // 顶部双 Tab:如愿(对话)｜如意(远程电脑)。远程控制从抽屉里的一页提升为平级入口。
    var selectedTab by rememberSaveable { mutableStateOf(RootTab.Ruyuan) }
    // 悬浮球点击时递增,通知远程抽屉(如意 Tab)把它自己收起来;0 为初始值。
    var closeRemoteDrawer by rememberSaveable { mutableStateOf(0) }
    var filesFromDrawer by rememberSaveable { mutableStateOf(false) }
    var filesConversation by rememberSaveable { mutableStateOf<String?>(null) }
    // 从设置页「重新配对」进来时先回到配对一步
    var setupRepair by rememberSaveable { mutableStateOf(false) }
    // 首次上手正在走哪一步;为空时不在上手流程里。配置模型页从哪儿来就回哪儿去
    var onboarding by rememberSaveable { mutableStateOf<OnboardingStep?>(null) }
    val onFix: (Fix) -> Unit = { fix ->
        when (fix) {
            Fix.AddModel -> nav.push(Route.Models)
            Fix.OpenPhoneSetup, Fix.RepairPhoneHelper -> {
                setupRepair = fix == Fix.RepairPhoneHelper
                nav.push(Route.PhoneSetup)
            }
            else -> activityFix(fix)
        }
    }
    var runId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceLegacyText by rememberSaveable { mutableStateOf<String?>(null) }
    val status by monitor.status.collectAsState()
    val roles by store.roles.collectAsState()
    val hasMain = roles.main != null
    val check = Prerequisites.check(status, hasMain)
    val app = context.applicationContext as MikaApp
    // 查到的新版本:侧栏、设置首页、关于页的红点都看它
    val updateState by app.updates.state.collectAsState()
    val autoUpdate by app.automaticUpdates.enabled.collectAsState()
    val readyUpdate by app.automaticUpdates.ready.collectAsState()
    LaunchedEffect(requestedConversation) {
        if (requestedConversation != null) {
            if (requestedConversation == "personal-main") nav.show(Route.Personal)
            else {
                app.chat.openConversation(requestedConversation)
                nav.reset()
            }
            onConversationOpened()
        }
    }
    LaunchedEffect(requestedCalls, requestedCallHistoryId) {
        if (requestedCalls) {
            focusedCallHistoryId = requestedCallHistoryId
            nav.push(Route.Calls)
            onCallsOpened()
        }
    }
    LaunchedEffect(sharedDraft) {
        val draft = sharedDraft?.draft
        if (draft != null) {
            val accepted = app.chat.openSharedDraft(draft, inCurrentConversation = sharedDraft.inCurrentConversation)
            if (accepted) nav.reset()
            onSharedDraftOpened(accepted, draft)
        }
    }
    // 消费任务或内部通知入口提交的自身操作请求。
    suspend fun openSelfFile(file: com.wangzan101.mika.self.SelfFileRequest) {
        if (!AttachFiles.openSent(context, file.conversationId, file.path)) {
            Toast.makeText(context, R.string.self_file_failed, Toast.LENGTH_SHORT).show()
        }
    }
    fun openSelfUrl(url: String, newTab: Boolean = false, show: Boolean = true) {
        nav.push(Route.Browser)
        val browser = UserBrowser.of(context)
        val error = if (newTab) browser.openInNewTab(url, select = show) else browser.open(url)
        error?.let { Toast.makeText(context, R.string.self_browser_failed, Toast.LENGTH_SHORT).show() }
    }
    fun openSelfLogin(host: String) {
        nav.push(Route.Browser)
        UserBrowser.of(context).openFavorite(com.wangzan101.mika.browser.FavoriteSite(host, loggedIn = false))
            ?.let { Toast.makeText(context, R.string.self_browser_failed, Toast.LENGTH_SHORT).show() }
    }
    val selfRequest by app.selfUi.request.collectAsState()
    LaunchedEffect(selfRequest?.id) {
        val r = selfRequest ?: return@LaunchedEffect
        when {
            r.url != null -> openSelfUrl(r.url, r.newTab, r.show)
            r.loginHost != null -> openSelfLogin(r.loginHost)
            r.file != null -> openSelfFile(r.file)
            r.screen != null -> nav.push(r.screen.route())
        }
        app.selfUi.consumed(r.id)
    }
    val release = remember { Build.VERSION.RELEASE }
    // 结果卡片上点了「看看」:打开技能分段
    val memoryRuntime = remember { MemoryRuntime.of(context) }
    val phoneSetup = remember { PhoneServices.of(context).setupPrompt }
    val openMemory by memoryRuntime.openRequest.collectAsState()
    LaunchedEffect(openMemory) { if (openMemory != null) nav.push(Route.Memory) }
    LaunchedEffect(openTeach) {
        if (openTeach) {
            nav.push(Route.Teach)
            onTeachOpened()
        }
    }
    // 第一次打开:欢迎 → 配置模型 → 手机操作;配过模型的跳过前两步,手机操作已就绪或看过引导的跳过最后一步
    val onboardingState = remember { OnboardingState(context) }
    fun onboardingInputs() = Onboarding.Inputs(
        hasMainModel = store.roles.value.main != null,
        modelSetupDone = onboardingState.modelSetupDone(),
        phoneNeeded = status.builtinShell != BuiltinShellStatus.Running && status.sdkInt >= Prerequisites.MIN_SDK,
        phoneGuideSeen = phoneSetup.guideSeen(),
    )
    fun goOnboarding(step: OnboardingStep?) {
        onboarding = step
        when (step) {
            // 上手的每一步都直接放在对话页上面一层,做完回到对话页
            OnboardingStep.Welcome -> nav.show(Route.Welcome)
            OnboardingStep.Model -> nav.show(Route.ModelSetup)
            OnboardingStep.Phone -> { setupRepair = false; nav.show(Route.PhoneSetup) }
            null -> nav.reset()
        }
    }
    LaunchedEffect(Unit) {
        if (onboarding != null) return@LaunchedEffect
        // 还没登录如意:先停在登录页,登录成功或点「暂不登录」后再走正常上手流程
        val ruyiLoggedIn = store.ruyiAccount()?.let { store.hasKey(it.id) } == true
        when {
            !ruyiLoggedIn -> nav.show(Route.RuyiLogin)
            // 深链直接落到某个会话、或默认就停在个人页:这时候不拿上手流程盖上去
            requestedConversation == null && nav.current != Route.Personal ->
                Onboarding.start(onboardingInputs())?.let(::goOnboarding)
        }
    }
    val phoneSetupNeeded by phoneSetup.needed.collectAsState()
    // 页面有自己的返回处理时(设置页里的子页、侧栏)由它们先接;这里只管退回上一页
    // 二级页面的返回只在「如愿」Tab 里处理;「如意」Tab 的返回交给 RemoteScreen 内部(它自己有一整条栈)。
    BackHandler(enabled = selectedTab == RootTab.Ruyuan && nav.canGoBack) { nav.back() }
    val back: () -> Unit = { nav.back() }
    // 聊天页离开组合树时保留列表位置和输入内容，返回设置页等子页面后原样恢复。
    val screenStates = rememberSaveableStateHolder()
    // 【临时】先隐藏顶部悬浮胶囊,只看悬浮球的效果;改回 true 即恢复顶部导航。
    val showTopNav = false
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (showTopNav) {
                // 顶部居中悬浮导航胶囊:浮在状态栏下方,圆角玻璃底 + 阴影,不占满宽。
                Box(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    RootFloatingNav(selectedTab, onSelect = { selectedTab = it })
                }
            }
            // 内容区:顶部胶囊显示时消费状态栏 inset(胶囊已避让状态栏),隐藏时让各页面自己留白。
            Box(Modifier.weight(1f).then(if (showTopNav) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
            // 如愿(对话) —— 先组合:它的常驻 BackHandler(侧栏)先注册、优先级更低;
            // 如意(远程电脑)放在其后组合,让它的 active 门控 BackHandler 后注册,
            // 如意 Tab 选中时优先吃掉返回键,抽屉展开按返回能收起而非退出 App。
            // 渲染层级由 tabContent 的 zIndex 独立控制,调换组合顺序不影响视觉。
            Box(Modifier.fillMaxSize().tabContent(selectedTab == RootTab.Ruyuan)) {
                when (nav.current) {
                    Route.PhoneSetup -> PhoneSetupScreen(status, setupRepair, onFix, onboarding = onboarding == OnboardingStep.Phone) {
                        phoneSetup.markGuideSeen()
                        if (onboarding == OnboardingStep.Phone) goOnboarding(null) else nav.back()
                    }
                    Route.RuyiLogin -> RuyiLoginScreen(
                        store,
                        onSignedIn = { nav.reset(); Onboarding.start(onboardingInputs())?.let(::goOnboarding) },
                        onSkip = { nav.reset(); Onboarding.start(onboardingInputs())?.let(::goOnboarding) },
                    )
                    Route.Welcome -> WelcomeScreen { goOnboarding(OnboardingStep.Model) }
                    Route.ModelSetup -> {
                        val inOnboarding = onboarding == OnboardingStep.Model
                        ModelSetupScreen(
                            store, onboarding = inOnboarding,
                            onBack = { if (inOnboarding) goOnboarding(OnboardingStep.Welcome) else nav.back() },
                            onDone = {
                                if (inOnboarding) {
                                    onboardingState.markModelSetupDone()
                                    goOnboarding(Onboarding.next(OnboardingStep.Model, onboardingInputs()))
                                } else nav.back()
                            },
                        )
                    }
                    Route.Chat, Route.Personal -> screenStates.SaveableStateProvider(nav.current.name) {
                        // 对话页重新看得见(回到前台、从别的页面回来):补上别处存进来的轮次,接上别处开始的任务
                        LifecycleStartEffect(Unit) {
                            app.chat.refresh()
                            onStopOrDispose {}
                        }
                        ChatWithDrawer(
                            vm = app.chat,
                            check = check,
                            reduceMotion = MikaTheme.reduceMotion,
                            conversations = { ConversationStore.shared(context).list(includeArchived = true) },
                            conversationChanges = ConversationStore.shared(context).changes,
                            conversationTitle = { ConversationStore.shared(context).title(it) },
                            search = ConversationStore.shared(context).search,
                            onPlans = { nav.pushFromDrawer(Route.Plans) },
                            onMemory = { nav.pushFromDrawer(Route.Memory) },
                            onSettings = { nav.pushFromDrawer(Route.Settings) },
                            onBrowser = { nav.pushFromDrawer(Route.Browser) },
                            onFiles = { filesFromDrawer = true; filesConversation = app.chat.ui.value.conversationId; nav.pushFromDrawer(Route.Files) },
                            onModels = { nav.pushFromDrawer(Route.Models) },
                            onMcp = { nav.pushFromDrawer(Route.Mcp) },
                            onScreens = { nav.pushFromDrawer(Route.Screens) },
                            onRemote = { selectedTab = RootTab.Ruyi },
                            onAbout = { nav.pushFromDrawer(Route.About) },
                            updateAvailable = AboutCopy.drawerDot(updateState),
                            reopen = nav.reopenDrawer,
                            onReopened = nav::drawerReopened,
                            onPersonal = { nav.show(Route.Personal) },
                            onRegularChat = nav::reset,
                            resetKey = nav.resetSerial,
                        ) { openDrawer ->
                            Box(Modifier.fillMaxSize()) {
                                val readyRelease = readyUpdate?.takeIf { autoUpdate && it.apkUrl == updateState.update?.apkUrl }
                                val updateBackdrop = if (readyRelease != null) rememberGlassBackdrop() else null
                                Box(Modifier.fillMaxSize().glassSource(updateBackdrop)) {
                                    if (nav.current == Route.Personal) PersonalAgentScreen(
                                        runtime = app.personal,
                                        voice = app.voice,
                                        onOpenDrawer = openDrawer,
                                        onOpenTask = { id -> app.chat.openPersonalTask(id); nav.reset() },
                                        memoryRuntime = memoryRuntime,
                                        onModels = { nav.push(Route.Models) },
                                        onPickSpeech = { nav.push(Route.SpeechModel) },
                                    ) else ChatScreen(
                                        vm = app.chat,
                                        check = check,
                                        frames = app.taskState.frames,
                                        webOpen = app.tasks.webScreenOpen,
                                        pipInput = app.taskState.pipInput,
                                        pipSoundOn = app.taskState.pipSoundOn,
                                        pipOperateEnabled = FeatureFlags.get(context).pipOperate(),
                                        reduceMotion = MikaTheme.reduceMotion,
                                        onOpenDrawer = openDrawer,
                                        onOpenFiles = { filesFromDrawer = false; filesConversation = app.chat.ui.value.conversationId; nav.push(Route.Files) },
                                        openSettings = { nav.push(Route.Settings) },
                                        openRun = { runId = it; nav.push(Route.Run) },
                                        phoneSetupNeeded = phoneSetupNeeded && status.builtinShell != BuiltinShellStatus.Running,
                                        openPhoneSetup = { onFix(Fix.OpenPhoneSetup) },
                                        loadSource = { id ->
                                            withContext(Dispatchers.IO) { evidenceSource(RunStore(context).events(id)) { ConfirmationNotifier.appLabel(context, it) } }
                                        },
                                        openSource = { id, legacy ->
                                            sourceRunId = id
                                            sourceLegacyText = legacy
                                            nav.push(Route.Source)
                                        },
                                        voice = app.voice,
                                        openSpeechModel = { nav.push(Route.SpeechModel) },
                                        onOpenTeach = { hint ->
                                            app.teach.prefill(hint)
                                            nav.push(Route.Teach)
                                        },
                                    )
                                }
                                var installUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
                                readyRelease?.let { release ->
                                    UpdateReadyCapsule(
                                        release,
                                        onInstall = {
                                            installUpdate = release
                                            app.automaticUpdates.dismiss(release)
                                        },
                                        onTimeout = { app.automaticUpdates.dismiss(release) },
                                        onDisable = { app.automaticUpdates.setEnabled(false) },
                                        backdrop = updateBackdrop,
                                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 54.dp),
                                    )
                                }
                                installUpdate?.let { release ->
                                    UpdateSheet(release, BuildConfig.VERSION_NAME, BuildConfig.BETA,
                                        onOpenUrl = { openInBrowser(context, it) }, onDismiss = { installUpdate = null })
                                }
                            }
                        }
                    }
                    Route.Settings -> {
                        val autonomous by app.approvals.autonomous.collectAsState()
                        SettingsScreen(store, status, release, onFix, onBack = back, onOpenMemory = { nav.push(Route.Memory) },
                            onOpenModels = { nav.push(Route.Models) },
                            autonomous = autonomous, onAutonomous = app.approvalControl::setAutonomous,
                            updateVersion = updateState.update?.version, onOpenAbout = { nav.push(Route.About) },
                            mcpServers = app.mcpServers)
                    }
                    Route.Models -> ModelsScreen(store, onBack = back, onOpenSpeech = { nav.push(Route.SpeechModel) }, onOpenUsage = { nav.push(Route.Usage) })
                    Route.Usage -> UsageScreen(app.usage, store, onBack = back, onOpenUrl = { openInBrowser(context, it) })
                    Route.SpeechModel -> SpeechModelScreen(store, onBack = back)
                    Route.Mcp -> McpServersScreen(app.mcpServers, onBack = back)
                    Route.About -> AboutScreen(
                        versionName = BuildConfig.VERSION_NAME, build = BuildConfig.GIT_HASH, beta = BuildConfig.BETA,
                        update = updateState,
                        issueUrl = remember {
                            MikaLinks.issueUrl(
                                MikaLinks.Device(
                                    Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                                    BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH,
                                ),
                            )
                        },
                        onCheck = app.updates::checkInBackground,
                        autoUpdate = autoUpdate,
                        onAutoUpdate = app.automaticUpdates::setEnabled,
                        onOpenUrl = { openInBrowser(context, it) },
                        onOpenLicense = { nav.push(Route.License) },
                        onBack = back,
                        diagnostics = {
                            Diagnostics.render(DiagnosticsCollector.collect(context, status))
                        },
                    )
                    Route.License -> LicensesScreen(onBack = back)
                    Route.Browser -> BrowserScreen(remember { UserBrowser.of(context) }, onBack = back)
                    Route.Files -> FileLibraryScreen(app.chat, filesConversation, filesFromDrawer, onBack = back, onAdded = { id ->
                        app.chat.openConversation(id)
                        val opened = app.chat.ui.value.conversationId == id
                        if (opened) nav.reset()
                        opened
                    }, onOpenSource = { id, target ->
                        app.chat.openConversationAt(id, target)
                        val opened = app.chat.ui.value.conversationId == id
                        if (opened) nav.reset()
                        opened
                    })
                    Route.Memory -> {
                        val registry = remember { WebSites.registry(context) }
                        val sites by registry.entries.collectAsState()
                        val webSites = WebSitesState(
                            sites = sites,
                            onOpenLogin = { WebHandover.showLogin(context, it) },
                            onLogout = { WebSites.login(context).logout(it) },
                            onLogoutAll = { WebSites.logoutAll(context) },
                        )
                        val routeStore = remember { FileRouteStore.shared(context) }
                        var routeCount by remember { mutableStateOf<Int?>(null) }
                        // 第一次取记忆要读盘解密,放到后台线程;页面的协程跟着这一屏走
                        val vmScope = rememberCoroutineScope()
                        LaunchedEffect(Unit) { routeCount = withContext(Dispatchers.IO) { runCatching { routeStore.count() }.getOrNull() } }
                        val vm by produceState<MemoryViewModel?>(null) {
                            value = withContext(Dispatchers.IO) {
                                val bundled = GuideCatalog.loadBundled()
                                MemoryViewModel(
                                    memoryRuntime.store(), memoryRuntime.settings(), memoryRuntime.skills(),
                                    vmScope, memoryRuntime.file().brokenNotice,
                                    wipe = { memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.wipeAll() } },
                                    onForgotten = { id -> memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.stripMemory(setOf(id)) } },
                                    runnableScripts = bundled.runnableScripts(),
                                    appDrafts = memoryRuntime.appKnowledge(),
                                    builtinSkillNames = bundled.items.map { it.name }.toSet(),
                                    onSkillRetired = { app.teach.forget(it) },
                                )
                            }
                        }
                        vm?.let { m ->
                            // 从结果卡片的「看看」进来:直接看技能分段
                            LaunchedEffect(m) {
                                val open = memoryRuntime.openRequest.value
                                if (open != null) m.select(if (open.draftId != null) Segment.Pending else Segment.Skills)
                            }
                            MemoryDataScreen(
                                m, webSites, routeCount,
                                onClearRoutes = {
                                    vmScope.launch { routeCount = withContext(Dispatchers.IO) { routeStore.clear(); routeStore.count() } }
                                },
                                onBack = { memoryRuntime.requestOpen(null); nav.back() },
                            )
                        }
                    }
                    Route.Run -> {
                        val id = runId
                        val vm = remember(id) { id?.let { RunTimelineViewModel(
                                RunStore(context), it, resolveMemory = app.chat::usedMemoryTexts,
                                alive = { run -> app.taskState.runs.value.any { r -> r.runId.value == run } },
                            ) } }
                        if (vm != null) RunTimelineScreen(vm, back) else LaunchedEffect(Unit) { nav.back() }
                    }
                    Route.Source -> {
                        val vm = remember(sourceRunId, sourceLegacyText) {
                            SourceViewModel(RunStore(context), sourceRunId, sourceLegacyText) { ConfirmationNotifier.appLabel(context, it) }
                        }
                        SourceScreen(vm, back)
                    }
                    Route.Plans -> PlansScreen(context, onBack = back, onOpenConversation = {
                        app.chat.openConversation(it)
                        nav.reset()
                    })
                    Route.Teach -> TeachScreen(
                        app.teach,
                        onBack = { nav.back() },
                        onOpenDrafts = {
                            memoryRuntime.requestOpen(com.wangzan101.mika.memory.MemoryOpen(app.teach.ui.value.session?.draftId))
                            nav.push(Route.Memory)
                        },
                    )
                    Route.Screens -> ScreensScreen(app.taskState, onBack = back, onOpenConversation = {
                        app.chat.openConversation(it)
                        nav.reset()
                    })
                    Route.Calls -> CallSettingsScreen(app.calls, onBack = back, standalone = true, initialHistoryCallId = focusedCallHistoryId)
                }
            }
            // 如意(远程电脑)常驻组合:切走只隐藏不销毁,保住 session/连接与导航栈。
            // 切回如意恢复切走前停留的页面(会话详情/设置等),不重置导航栈。
            Box(Modifier.fillMaxSize().tabContent(selectedTab == RootTab.Ruyi)) {
                RemoteScreen(
                    callback = remoteCallback,
                    onCallbackConsumed = onRemoteCallbackConsumed,
                    onBack = { selectedTab = RootTab.Ruyuan },
                    active = selectedTab == RootTab.Ruyi,
                    closeDrawerRequest = closeRemoteDrawer,
                )
            }
        }
    }
        // 悬浮球只在主界面(如愿对话/如意远程两个顶层 Tab 已就绪)显示;
        // 登录页、上手引导(欢迎/配置模型/手机操作)等页面还没有这两个 Tab,不显示。
        if (nav.current == Route.Chat || nav.current == Route.Personal) {
            FloatingDock(
                selectedTab,
                onSelect = { selectedTab = it },
                // 点悬浮球本体时,若远程抽屉(如意 Tab)正展开,把它一并收起。
                onBallClick = { closeRemoteDrawer++ },
            )
        }
    }
}

/**
 * 底部 Tab 内容的显示控制:常驻组合以保留状态,但未选中时隐藏 + 吞掉触摸事件,
 * 否则透明层仍会拦截可见 Tab 的点击,并让隐藏的滚动/按钮「隔空」响应。
 */
private fun Modifier.tabContent(visible: Boolean): Modifier =
    this
        .zIndex(if (visible) 1f else 0f)
        .alpha(if (visible) 1f else 0f)
        .then(
            if (visible) Modifier
            else Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
        )

/** 顶部悬浮导航胶囊:如愿(对话)｜如意(远程电脑)。玻璃圆角底 + 阴影,选中项实心高亮。 */
@Composable
private fun RootFloatingNav(selected: RootTab, onSelect: (RootTab) -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier
            .floatShadow(c, MikaShapes.Pill)
            .clip(MikaShapes.Pill)
            .background(c.glass)
            .border(0.5.dp, c.glassEdge, MikaShapes.Pill)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RootNavItem(MikaIcons.Message, "如愿", selected == RootTab.Ruyuan) { onSelect(RootTab.Ruyuan) }
        RootNavItem(MikaIcons.Desktop, "如意", selected == RootTab.Ruyi) { onSelect(RootTab.Ruyi) }
    }
}

@Composable
private fun RootNavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier
            .clip(MikaShapes.Pill)
            .background(if (selected) c.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (selected) c.onAccent else c.ink2)
        Text(
            label,
            style = MikaType.Hint,
            color = if (selected) c.onAccent else c.ink2,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * 可拖拽的悬浮导航球:平时是一个蓝色小球,点开横向平铺弹出「如愿 / 如意」两个菜单项,
 * 点空白处收起;按住拖动可移到任意位置。平铺朝屏幕内侧展开(球在右半屏朝左排、左半屏朝右排),
 * 因此按钮能贴到左右边缘;展开用 spring + 错峰 + 缩放,手感更丝滑。
 */
@Composable
private fun FloatingDock(selected: RootTab, onSelect: (RootTab) -> Unit, onBallClick: () -> Unit = {}) {
    val c = MikaTheme.colors
    val density = LocalDensity.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 悬浮球圆心的像素坐标;-1 表示还没落位,首次布局贴右下角。
    var centerX by rememberSaveable { mutableStateOf(-1) }
    var centerY by rememberSaveable { mutableStateOf(-1) }
    // 点开时球明显放大回弹,更跟手。
    val ballScale by animateFloatAsState(
        targetValue = if (expanded) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 480f),
        label = "dockBall",
    )
    // 展开时球上的图标随球转 90°,Apps → Close 的切换更有动感。
    val ballRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "dockBallRot",
    )

    val buttonPx = with(density) { 52.dp.roundToPx() }
    val halfButtonPx = buttonPx / 2
    val itemW = with(density) { 84.dp.roundToPx() }
    val itemH = with(density) { 40.dp.roundToPx() }
    val marginPx = with(density) { 16.dp.roundToPx() }
    val topSafePx = with(density) { 20.dp.roundToPx() }
    // 底部要避开聊天输入栏:导航栏 inset + 输入框玻璃条及其上下留白,悬浮球默认落在输入框上方。
    val bottomReservePx = WindowInsets.navigationBars.getBottom(density) + with(density) { 76.dp.roundToPx() }
    // 横向平铺:两个菜单项与球心同高、朝屏幕内侧一字排开(球 → 如意 → 如愿)。
    // 两按钮总宽 + 球半径 ≈ 226dp(球半径 26 + 按钮宽 84×2 + 间距 16×2),略大于半屏宽。
    // 纵向只按按钮半高 + 边距即可(按钮与球心同高,不再上下错开)。
    val flatReachY = itemH / 2 + marginPx

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        // 默认贴右下、但在输入框上方(平铺朝左上展开)。
        val cur = if (centerX >= 0) IntOffset(centerX, centerY)
                  else IntOffset(maxW - halfButtonPx - marginPx, maxH - halfButtonPx - bottomReservePx)
        // 球在右半屏就朝左排、左半屏朝右排,按钮始终朝屏幕内侧展开。
        // 球心横向拖拽范围与展开方向解耦:球可在全屏左右自由拖动(只留贴边余量),
        // 不再被「展开方向」卡在某一半屏。仅当球心落在正中间约 40dp 窄带时,按钮
        // 展开会轻微贴边 —— 这是横向平铺两按钮总宽 226dp 略大于半屏 205dp 的固有权衡。
        val openLeft = cur.x > maxW / 2
        val minCy = topSafePx + flatReachY
        val maxCy = maxH - halfButtonPx - bottomReservePx

        // 展开时铺一层透明遮罩,点空白处收起。
        if (expanded) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = false },
            )
        }

        val items = listOf(
            Triple(MikaIcons.Message, "如愿", RootTab.Ruyuan),
            Triple(MikaIcons.Desktop, "如意", RootTab.Ruyi),
        )
        // 每个菜单项一条独立 spring 进度,展开时依次错峰、收起时同步淡出。
        val anims = remember { items.map { Animatable(0f) } }
        LaunchedEffect(expanded) {
            if (expanded) {
                anims.forEachIndexed { i, a ->
                    launch {
                        delay(i * 70L)
                        // 低阻尼 spring 产生明显过冲:项先飞出过头、再弹回水平线,动态感更强。
                        a.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 360f))
                    }
                }
            } else {
                anims.forEachIndexed { i, a ->
                    launch { a.animateTo(0f, tween(110, easing = FastOutSlowInEasing)) }
                }
            }
        }
        // 横向平铺:与球心同高、朝内侧一字排开;如意(items[1])靠球、如愿(items[0])靠外。
        items.forEachIndexed { index, (icon, label, tab) ->
            val p = anims[index].value
            val slot = if (index == 0) 1 else 0
            val distFromBallCenter = halfButtonPx + marginPx + itemW / 2 + slot * (itemW + marginPx)
            val dx = (if (openLeft) -distFromBallCenter else distFromBallCenter) * p
            DockItem(
                icon, label, selected == tab,
                onClick = { onSelect(tab); expanded = false },
                modifier = Modifier
                    .offset { IntOffset(cur.x + dx.roundToInt() - itemW / 2, cur.y - itemH / 2) }
                    .graphicsLayer {
                        alpha = p.coerceIn(0f, 1f)
                        scaleX = 0.2f + 0.8f * p
                        scaleY = 0.2f + 0.8f * p
                    },
            )
        }

        // 悬浮球本体。
        Box(
            Modifier
                .offset { IntOffset(cur.x - halfButtonPx, cur.y - halfButtonPx) }
                .size(52.dp)
                // 球体只做缩放(各向同性,自抵消,不干扰拖拽);旋转放到图标上,避免
                // graphicsLayer 的 rotationZ 把触摸坐标也旋 90°,导致拖拽 x/y 分量交换、手感发飘。
                .graphicsLayer {
                    scaleX = ballScale
                    scaleY = ballScale
                }
                .floatShadow(c, MikaShapes.Pill)
                .clip(MikaShapes.Pill)
                .background(c.accent)
                .border(0.5.dp, c.glassEdge, MikaShapes.Pill)
                .pointerInput(maxW, maxH) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            val baseX = if (centerX >= 0) centerX else cur.x
                            val baseY = if (centerY >= 0) centerY else cur.y
                            val candX = baseX + amount.x
                            val candY = baseY + amount.y
                            // 球心横向全屏自由拖动,只留贴边余量;不再按「展开方向」把球
                            // 卡在某一半屏。此前 flatReachX(226dp) > 半屏(205dp),按展开
                            // 方向 clamp 会在中线处留下约 40dp 死区,慢拖时球跨不过中线。
                            val loX = halfButtonPx + marginPx
                            val hiX = maxW - halfButtonPx - marginPx
                            centerX = candX.roundToInt().coerceIn(loX, hiX)
                            centerY = candY.roundToInt().coerceIn(minCy, maxCy)
                        },
                    )
                }
                .clickable {
                    expanded = !expanded
                    // 点球本体时,远程抽屉(如意 Tab)若展开也一并收起。
                    onBallClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (expanded) MikaIcons.Close else MikaIcons.Apps,
                null,
                Modifier.size(22.dp).graphicsLayer { rotationZ = ballRotation },
                tint = c.onAccent,
            )
        }
    }
}

@Composable
private fun DockItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    Row(
        modifier
            .size(width = 84.dp, height = 40.dp)
            .floatShadow(c, MikaShapes.Pill)
            .clip(MikaShapes.Pill)
            .background(if (selected) c.accent else c.surface)
            .border(0.5.dp, c.glassEdge, MikaShapes.Pill)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (selected) c.onAccent else c.ink)
        Spacer(Modifier.size(6.dp))
        Text(label, style = MikaType.Label, color = if (selected) c.onAccent else c.ink)
    }
}

/** 自身工具能打开的 Mika 顶层页;只给这几页,不给权限/root/开发者子页。 */
private fun com.wangzan101.mika.core.self.SelfScreen.route(): Route = when (this) {
    com.wangzan101.mika.core.self.SelfScreen.Chat -> Route.Chat
    com.wangzan101.mika.core.self.SelfScreen.Settings -> Route.Settings
    com.wangzan101.mika.core.self.SelfScreen.Memory -> Route.Memory
    com.wangzan101.mika.core.self.SelfScreen.Plans -> Route.Plans
    com.wangzan101.mika.core.self.SelfScreen.Browser -> Route.Browser
    com.wangzan101.mika.core.self.SelfScreen.Models -> Route.Models
    com.wangzan101.mika.core.self.SelfScreen.Usage -> Route.Usage
    com.wangzan101.mika.core.self.SelfScreen.Screens -> Route.Screens
    com.wangzan101.mika.core.self.SelfScreen.About -> Route.About
}
