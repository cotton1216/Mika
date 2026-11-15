package com.xxt.zl.ai.ruyiremote.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xxt.zl.ai.ruyiremote.account.CODE_ACCOUNT_LABEL
import com.xxt.zl.ai.ruyiremote.account.HostConnection
import com.xxt.zl.ai.ruyiremote.account.RemoteAccount
import com.xxt.zl.ai.ruyiremote.account.RemoteConnectException
import com.xxt.zl.ai.ruyiremote.account.RemoteHost
import com.xxt.zl.ai.ruyiremote.account.RemoteUser
import com.xxt.zl.ai.ruyiremote.net.RemoteApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "RemoteSession"

/** 账号状态。 [busy] 在控制器上，这里只表达「已登录 / 未登录 / 上次失败」。 */
sealed interface AccountPhase {
    data object SignedOut : AccountPhase
    data class SignedIn(val user: RemoteUser) : AccountPhase
    data class Failed(val message: String, val code: String? = null) : AccountPhase
}

/**
 * 界面与 [RemoteAccount] 之间的状态机。
 *
 * 放在 Compose 之外是为了让「登录 → 注册设备 → 列 Host → 连一条」这条链路能
 * 单独读：哪一步失败、失败在 REST 还是 Noise，都体现在 [errorCode] 上，而不是
 * 混在一堆 if 里。
 */
class RemoteSessionController(
    private val account: RemoteAccount,
    private val scope: CoroutineScope,
) {
    var phase: AccountPhase by mutableStateOf(AccountPhase.SignedOut)
        private set

    /**
     * 冷启动时「有存档但进不去」的原因。
     *
     * 只在 [phase] 是 SignedOut 时有意义：区分「第一次装」和「上次登录作废了」。
     */
    var signedOutNotice: String? by mutableStateOf(null)
        private set

    /** 登录/换票正在跑。用独立标记而不是伪造一个 SignedIn，免得界面先闪一下空账号。 */
    var busy: Boolean by mutableStateOf(false)
        private set

    var hosts: List<RemoteHost> by mutableStateOf(emptyList())
        private set

    var hostsLoading: Boolean by mutableStateOf(false)
        private set

    var hostsError: String? by mutableStateOf(null)
        private set

    /** 正在连的 Host id。 */
    var connectingHostId: String? by mutableStateOf(null)
        private set

    /** 上一次连接的结果，成功才非空。 */
    var connection: HostConnection? by mutableStateOf(null)
        private set

    /**
     * 当前连着的电脑。
     *
     * [HostConnection] 里没有 hostId（那一层只认隧道，不认账号侧的 Host 记录），
     * 而界面需要「这台机器是不是就是已连上的那台」来判断按钮文案与列表徽标，
     * 所以在控制器上并排记一份。它和 [connection] 同生命周期。
     */
    var connectedHost: RemoteHost? by mutableStateOf(null)
        private set

    var connectionSummary: String? by mutableStateOf(null)
        private set

    var connectionError: String? by mutableStateOf(null)
        private set

    /**
     * 通道被动断开后给用户的一句话。
     *
     * 和 [connectionError] 分开：那个是「连接失败」，这个是「连上之后又被断了」，
     * 界面位置也不同（电脑列表顶部提示，而不是连接卡片里的红字）。
     */
    var transportNotice: String? by mutableStateOf(null)
        private set

    /** 通道掉了、正在自动重连。界面据此显示「正在重连」而不是「已断开」。 */
    var reconnecting: Boolean by mutableStateOf(false)
        private set

    /**
     * 连接尝试代号。见 [ConnectionEpoch]：切主机、断开、登出都会让旧回包失效。
     */
    private val connectionEpoch = ConnectionEpoch()

    val serverUrl: String get() = account.serverUrl

    /**
     * 冷启动恢复登录态还没结束。
     *
     * 只用于界面遮盖：恢复完成前 [phase] 一律是 SignedOut，直接渲染会先画出配对页。
     */
    var booting: Boolean by mutableStateOf(true)
        private set

    /** 冷启动恢复：有凭据就直接进已登录态。 */
    fun restore() {
        scope.launch {
            try {
                val user = runCatching { account.currentUser() }.getOrNull()
                if (user != null) {
                    signedOutNotice = null
                    phase = AccountPhase.SignedIn(user)
                    refreshHosts()
                } else {
                    // 存档存在但用不了时给登录页一句话，而不是让用户对着配对码猜。
                    signedOutNotice = runCatching { account.restoreIssue() }.getOrNull()
                }
            } finally {
                booting = false
            }
        }
    }

    fun loginWithPassword(email: String, password: String) = run("密码登录") {
        account.loginWithPassword(email, password)
    }

    fun loginWithCode(code: String) = run("配对码登录") {
        account.loginWithCode(code)
    }

    /**
     * 用如意 IAM 换来的账号令牌登录。
     *
     * 这里是一处**已知的边界**：中继的 `/api/v1/devices/register` 只认它自己签发的
     * 账号会话（或配对码），不认 AI-Server 的桌面令牌。所以这条路上大概率收到
     * `ACCOUNT_AUTH_REQUIRED` —— 那时 UI 会明确提示改用配对码，而不是含糊地
     * 「登录失败」。中继侧一旦提供手机端 OIDC 换票端点，这里不用改。
     */
    fun loginWithAccountToken(token: String, label: String) = run("如意账号登录") {
        account.loginWithAccountToken(token, label)
    }

    private fun run(
        what: String,
        action: suspend () -> RemoteUser,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val user = action()
                signedOutNotice = null
                phase = AccountPhase.SignedIn(user)
                refreshHosts()
            } catch (e: RemoteConnectException) {
                phase = AccountPhase.Failed(describe(e.code, what), e.code)
            } catch (e: RemoteApiException) {
                phase = AccountPhase.Failed(describe(e.code, what), e.code)
            } catch (e: Exception) {
                // 兜底分支必须留下类名：真机上出过一次 message 为 null 的异常，
                // 界面只剩「失败：null」，等于把线索全丢了。
                Log.w(TAG, "$what 未归类的异常", e)
                phase = AccountPhase.Failed("${what}失败：${e.message ?: e.javaClass.simpleName}", null)
            } finally {
                busy = false
            }
        }
    }

    fun refreshHosts() {
        if (hostsLoading) return
        hostsLoading = true
        hostsError = null
        scope.launch {
            try {
                hosts = account.listHosts().sortedWith(compareByDescending<RemoteHost> { it.online }.thenBy { it.name })
            } catch (e: Exception) {
                hostsError = describeWithCode(e, "读取电脑列表")
            } finally {
                hostsLoading = false
            }
        }
    }

    /**
     * 连到一台 Host。既用于「第一次连接」，也用于抽屉里的「切换主机」。
     *
     * 中继对同一个 client 只保留一条链路，所以切换不能假设「新的失败时旧的还
     * 健康」：一旦开始尝试，旧连接就可能已经被 CONNECTION_REPLACED。这里在发起
     * 请求前就把旧引用摘掉，并用 [ConnectionEpoch] 让旧 watcher 与迟到的回包全部
     * 失效，避免切换期间又自动连回旧主机。成功后才把新连接写回状态；失败则保持
     * 断开态，由用户在当前页面重试或改选另一台。
     */
    fun connect(host: RemoteHost) {
        if (connectingHostId != null) return
        val previous = connection
        val attempt = connectionEpoch.begin()
        connectingHostId = host.id
        connection = null
        connectedHost = null
        connectionError = null
        connectionSummary = null
        transportNotice = null
        reconnecting = false
        scope.launch {
            try {
                val opened = account.connect(host)
                if (!connectionEpoch.isCurrent(attempt)) {
                    // 用户已经断开 / 登出 / 又换了主机：这个回包没有资格改变界面。
                    runCatching { opened.close() }
                    return@launch
                }
                // 先接上新连接再关旧的，避免中间出现「界面以为已断开」的一帧。
                connection = opened
                connectedHost = host
                runCatching { previous?.close() }
                connectionSummary = buildString {
                    append("已连上 ${host.name}")
                    append(" · 通道 ${opened.transportMode}")
                    opened.harnessVersion?.let { append(" · 如意 $it") }
                    append("\n能力：")
                    append(opened.capabilities.joinToString("、"))
                }
                watchTransport(host, opened, attempt)
            } catch (e: Exception) {
                if (!connectionEpoch.isCurrent(attempt)) return@launch
                Log.w(TAG, "连接 ${host.name} 失败", e)
                // 请求可能已经走到中继建链，旧链路不再可信；不能把它恢复成当前连接。
                runCatching { previous?.close() }
                connection = null
                connectedHost = null
                connectionError = describeWithCode(e, "连接 ${host.name}")
            } finally {
                if (connectionEpoch.isCurrent(attempt)) connectingHostId = null
            }
        }
    }

    /**
     * 通道被对端或网络关掉时主动收尾。
     *
     * RPC 层只是把在飞请求判失败，界面上还留着「已连接」和旧列表；如果没人把
     * [connection] 置空，用户会停在一个所有操作都失败的页面里，且没有任何入口重连。
     * 所以先原地自动重连（退避 1/2/3/5/8/13/20/30 秒，约 80 秒窗口），失败再退回
     * 「已断开 + 提示」。窗口太长会让用户以为卡死，太短则撑不过一次地铁进隧道。
     */
    private fun watchTransport(host: RemoteHost, opened: HostConnection, attempt: Long) {
        scope.launch {
            val reason = opened.rpc.closed.await()
            if (!connectionEpoch.isCurrent(attempt) || connection !== opened) return@launch
            Log.w(TAG, "通道断开：${reason.code}")
            reconnecting = true
            transportNotice = "与 ${host.name} 的连接中断，正在重新连接…"
            val backoff = longArrayOf(1_000, 2_000, 3_000, 5_000, 8_000, 13_000, 20_000, 30_000)
            for ((index, wait) in backoff.withIndex()) {
                delay(wait)
                // 切换主机 / 主动断开后，旧 watcher 必须就此停手：继续重连会把用户
                // 又拉回旧主机，而中继那侧只会看到一次多余的链路替换。
                if (!connectionEpoch.isCurrent(attempt) || connection !== opened) {
                    reconnecting = false
                    return@launch
                }
                val retried = runCatching { account.connect(host) }.getOrElse { error ->
                    Log.w(TAG, "第 ${index + 1} 次重连失败", error)
                    null
                }
                if (retried != null) {
                    if (!connectionEpoch.isCurrent(attempt)) {
                        runCatching { retried.close() }
                        reconnecting = false
                        return@launch
                    }
                    connection = retried
                    connectedHost = host
                    reconnecting = false
                    transportNotice = "已重新连接 ${host.name}。"
                    watchTransport(host, retried, attempt)
                    return@launch
                }
            }
            if (!connectionEpoch.isCurrent(attempt)) return@launch
            connection = null
            connectedHost = null
            connectionSummary = null
            reconnecting = false
            transportNotice = "与 ${host.name} 的连接已断开（${reason.code}），需要重新连接。"
        }
    }

    /** 从失败态回到登录表单：换个入口再试。 */
    fun backToSignIn() {
        phase = AccountPhase.SignedOut
    }

    /**
     * 系统网络丢失（`ConnectivityManager.onLost`）时调用。
     *
     * 只针对「已连上、又没在重连」的状态：立即让当前通道以 `CONNECTION_FAILED` 判死，
     * 把 [watchTransport] 里挂着的 `rpc.closed.await()` 叫醒，进入自动重连。静默断线
     * （无 FIN/RST）靠这个把 75s 心跳判死缩短到亚秒；主动断开/切换/未连接时是 no-op。
     */
    fun onNetworkLost() {
        val open = connection ?: return
        if (connectedHost == null) return
        if (reconnecting) return
        Log.i(TAG, "网络丢失，立即判死当前通道并触发自动重连")
        scope.launch { runCatching { open.onNetworkLost() } }
    }

    /** 主动断开，但保留账号与 pin（下次还能连）。未完成的切换也一并作废。 */
    fun disconnect() {
        connectionEpoch.invalidate()
        val open = connection
        connection = null
        connectedHost = null
        connectionSummary = null
        connectionError = null
        transportNotice = null
        connectingHostId = null
        reconnecting = false
        // 先摘掉引用再 close：否则通道关闭回调会把它当成「被动断线」弹提示。
        scope.launch { runCatching { open?.close() } }
    }

    fun forgetHost(host: RemoteHost) {
        scope.launch {
            runCatching { account.forgetHost(host.id) }
            refreshHosts()
        }
    }

    fun logout() {
        // 登出时可能还有一台主机正在切换；先作废它的回包，避免登出后又被写回
        // 一条「已连接」状态。
        connectionEpoch.invalidate()
        val open = connection
        connection = null
        connectedHost = null
        connectionSummary = null
        connectionError = null
        transportNotice = null
        connectingHostId = null
        reconnecting = false
        scope.launch {
            runCatching { open?.close() }
            runCatching { account.logout() }
            hosts = emptyList()
            phase = AccountPhase.SignedOut
        }
    }

    /** 账号名为配对码的局部标签时，UI 要翻成人话。 */
    fun accountLabel(user: RemoteUser): String =
        if (user.account == CODE_ACCOUNT_LABEL || user.account.isBlank()) "已绑定设备" else user.account

    private fun codeOf(e: Exception): String? = when (e) {
        is RemoteConnectException -> e.code
        is RemoteApiException -> e.code
        else -> null
    }

    /**
     * 在没有独立「诊断码」行的位置（列表、连接结果）用这个：
     * 未知码不至于只剩一句「失败了」，而是把原始码跟在后面。
     */
    private fun describeWithCode(e: Exception, what: String): String {
        val code = codeOf(e)
        val text = describe(code, what)
        return if (code != null && !text.contains(code)) "$text（$code）" else text
    }

    /** code → 人话。原文只用于诊断，不直接展示（可能是服务端英文）。 */
    private fun describe(code: String?, what: String): String = when (code) {
        "ACCOUNT_AUTH_REQUIRED" ->
            "${what}失败：中继不接受这个账号凭据。请改用「配对码」或「账号密码」登录。"
        // 中继对「配对码不存在 / 已用过 / 已过期」统一回 AUTH_INVALID，不区分原因。
        "AUTH_INVALID" ->
            "${what}失败：配对码不对或已过期。请到电脑上重新生成一个（一次性，用过即失效）。"
        "INVALID_CODE", "CODE_EXPIRED", "CODE_ALREADY_USED" ->
            "${what}失败：配对码不对或已过期，请重新生成。"
        "UNAUTHORIZED" -> "${what}失败：登录状态失效了，请重新登录。"
        "HOST_OFFLINE" -> "${what}失败：这台电脑离线（桌面端没开远程访问）。"
        "PEER_IDENTITY_MISMATCH" ->
            "${what}失败：这台电脑的身份密钥与之前记住的不一致，已拒绝连接。若是重装过，请点「重新信任」。"
        "HARNESS_VERSION_INCOMPATIBLE" -> "${what}失败：桌面端版本太旧，需要如意 0.2.x（Session V3）。"
        "DEVICE_REVOKED" -> "${what}失败：本设备已被吊销，请重新登录。"
        "RATE_LIMITED" -> "${what}失败：请求太频繁，稍后再试。"
        "CONNECTION_FAILED" -> "${what}失败：网络不通或中继暂时不可用。"
        "NOISE_DECRYPT_FAILED", "SECURE_CHANNEL_FAILED" ->
            "${what}失败：端到端加密握手没通过（多半是桌面端身份不对）。"
        null -> "${what}失败。"
        // 未知码不塞进正文：调用方有独立的「诊断码」行，重复一遍反而更难读。
        else -> "${what}失败。"
    }
}
