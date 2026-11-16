package com.xxt.zl.ai.ruyiremote.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.xxt.zl.ai.ruyiremote.account.KeystoreSecretStore
import com.xxt.zl.ai.ruyiremote.account.RemoteAccount
import com.xxt.zl.ai.ruyiremote.auth.RuyiAuth
import com.xxt.zl.ai.ruyiremote.links.DeepLinks
import com.xxt.zl.ai.ruyiremote.ui.theme.RuyiTheme
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val TAG = "RuyiRemoteHome"

/**
 * 应用入口：只负责把「账号」「登录」「中继」三个控制器接起来，然后交给 [DshApp] 画。
 *
 * 这里刻意不含任何布局代码。之前所有区块堆在一屏，正是因为入口既管状态又管排版，
 * 于是「再加一块内容」就只能往下追加一个 Card。
 *
 * 三种账号入口的取舍（对应 [AlternateSignInScreen]）：
 * - **配对码**是主路径。桌面端设置里直接生成 `XXXX-XXXX`，不经过任何第三方。
 * - **账号密码**是同一条 `/api/v1/auth/login` 的另一种形态，中继自己签发会话。
 * - **如意账号**走 AI-Server 桌面授权：授权在 AI-Server，**换票打中继**
 *   （`/api/v1/auth/aiserver/exchange`），拿到的是中继自己的会话令牌，
 *   所以能直接注册设备。前提是中继配了 `DSH_AISERVER_*` 与身份服务；
 *   没配时换票返回 404，界面会明说「这套中继未开启如意账号登录」。
 */
@Composable
fun RemoteHome(
    callback: String?,
    onCallbackConsumed: () -> Unit,
    // 库里不反向依赖宿主 MainActivity；宿主（App 壳或米卡）通过外部浏览器打开登录页，
    // 返回是否成功启动。默认不打开（纯库/测试场景）。
    openBrowser: (String?) -> Boolean = { false },
    /**
     * 是否在「其他登录方式」里保留如意账号登录。宿主自己已经有如意登录时传 false：
     * 设备在宿主那次登录里就注册好了，这里再摆一个入口就是第二个登录页。
     */
    showSso: Boolean = true,
    /**
     * 宿主（如愿）嵌入时传入：远程控制根页给一个显式「返回如愿」，返回键也退回宿主。
     * 独立 App 时为 null，它自己就是根。
     */
    onExit: (() -> Unit)? = null,
    /**
     * 宿主用底部 Tab 切换时，本页常驻组合以保留连接/导航栈；Tab 未选中时传 false，
     * 禁用返回键处理。独立 App 或常显时保持默认 true。
     */
    active: Boolean = true,
    /**
     * 宿主悬浮球点击递增的信号：抽屉展开时点悬浮球把抽屉一并收起。
     * 值无意义，只看变化；见 [DshApp.closeDrawerRequest]。
     */
    closeDrawerRequest: Int = 0,
    /**
     * 宿主（如愿）嵌入时传入：登录首屏给一条「回如愿登录」。
     * [showSso] 为 false 时本库不提供如意账号登录，联动的失败/退出提示必须能指回去。
     */
    onBackToHostSignIn: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val auth = remember {
        RuyiAuth(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build(),
        )
    }
    val account = remember { RemoteAccount(KeystoreSecretStore(context)) }
    val signIn = remember(scope) {
        SignInController(
            auth,
            openBrowser = { url -> openBrowser(url) },
            // 换票打的是中继，地址取用户当前配置（`RemoteAccount.serverUrl` 已按地址规范校验过）。
            relayBase = { account.serverUrl },
            scope = scope,
        )
    }
    val session = remember(scope) { RemoteSessionController(account, scope) }

    // 冷启动时可能已经有回跳在排队：先订阅，再把已有的那条补上。
    DisposableEffect(signIn) {
        DeepLinks.listen { uri -> signIn.onCallback(uri) }
        onDispose { DeepLinks.stopListening() }
    }

    LaunchedEffect(callback) {
        if (callback != null) {
            signIn.onCallback(callback)
            onCallbackConsumed()
        }
    }

    // 有凭据就直接进「我的电脑」，不必每次重启都重新登录。
    LaunchedEffect(session) { session.restore() }

    // 宿主（如愿）换了本机设备令牌：登录成功要认这个登录，退出要真的退回登录页。
    // 这一页在宿主的 Tab 里是常驻组合，不重读就一直是切换前那个账号的界面。
    // 重读本身是幂等的（[RemoteSessionController.restore] 双向），失败分类也由它回答。
    val hostAccountRevision by RemoteSso.revisions.collectAsState()
    LaunchedEffect(hostAccountRevision) {
        if (hostAccountRevision > 0) session.restore()
    }

    // 系统网络丢失 → 静默断线亚秒判死。否则只能靠中继心跳 watchdog（25s×3=75s）
    // 才意识到「网络没了」，断网后的自动重连会被拖慢一分多钟。
    DisposableEffect(session) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 网络恢复：切 Wi-Fi、关飞行模式、进电梯出来都会走这里。断网时间一旦
                // 超过重连窗口（约 82s，见 watchTransport 的退避表），connection 已被
                // 置空，只靠心跳不会自愈；这里补一次连接，别让用户对着「需要重新连接」
                // 发愣。已连接/已抑制（主动断开）时控制器内部是 no-op。
                mainHandler.post { session.onNetworkAvailable() }
            }

            override fun onLost(network: Network) {
                // 回调在 ConnectivityService 的 binder 线程上。这里只读系统状态，
                // 真正碰 Compose snapshot state 的动作一律 post 回主线程。
                val active = runCatching { connectivity.activeNetwork }.getOrNull()
                val lostIsActive = active == null || active == network
                val hasAlternative = runCatching {
                    connectivity.allNetworks.any { candidate ->
                        if (candidate == network) return@any false
                        val caps = connectivity.getNetworkCapabilities(candidate) ?: return@any false
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    }
                }.getOrElse { true } // 查不到就保守认为还有网：不误杀，交给 watchdog
                if (!shouldJudgeNetworkLost(lostIsActive, hasAlternative)) return
                mainHandler.post {
                    Log.i(TAG, "承载网络丢失且无替代，立即判死并触发自动重连")
                    session.onNetworkLost()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // 注册失败不能静默：否则界面看起来有「亚秒判死」，实际只剩 75s watchdog。
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "注册网络回调失败，静默断线检测降级为心跳 watchdog", it) }
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
                .onFailure { Log.w(TAG, "注销网络回调失败", it) }
        }
    }

    // 如意登录成功 → 用**中继**会话令牌把本机注册成设备。失败会落到 AccountPhase.Failed，
    // 由二级登录页解释原因，不在这里吞掉。
    val ssoPhase = signIn.phase
    LaunchedEffect(ssoPhase) {
        if (ssoPhase is SignInPhase.SignedIn) {
            session.loginWithAccountToken(ssoPhase.account.accountToken, ssoPhase.account.displayName)
        }
    }

    RuyiTheme {
        DshApp(
            session = session,
            signIn = signIn,
            showSso = showSso,
            onExit = onExit,
            active = active,
            closeDrawerRequest = closeDrawerRequest,
            onBackToHostSignIn = onBackToHostSignIn,
        )
    }
}
