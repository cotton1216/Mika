package com.xxt.zl.ai.ruyiremote.ui

import android.content.Context
import android.util.Log
import com.xxt.zl.ai.ruyiremote.account.KeystoreSecretStore
import com.xxt.zl.ai.ruyiremote.account.RemoteAccount
import com.xxt.zl.ai.ruyiremote.account.RemoteConnectException
import com.xxt.zl.ai.ruyiremote.net.DEFAULT_SERVER_URL
import com.xxt.zl.ai.ruyiremote.net.RemoteApiException
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 把「如意 AI App 的那一次登录」接到远程控制上，并负责它退出时的收尾。
 *
 * 为什么不是再登录一次：AI-Server 的授权码**一次性、60 秒过期**，同一个码换不出两份票。
 * 如意 AI 登录时码已经换成了 AI-Server 的 `access_token`，所以远程控制只能复用这张票 ——
 * 由中继把它换成账号会话，再把本机注册成 client 设备。中继读的是同一个 AI-Server
 * profile（`iamFromToken`），因此和远程自己走一遍授权落到**同一个账号主键**。
 *
 * 注册在本机落的是**设备令牌**（[RemoteAccount] 存进 Keystore），账号侧那张短时效票据
 * 用完即弃：之后 RemoteHome 的 `restore()` 直接进「我的电脑」，不会再问登录。
 *
 * 登录与退出必须成对：只做登录联动的话，下一个登录的人会直接继承上一个账号的电脑列表。
 */
object RemoteSso {
    private const val TAG = "RemoteSso"

    /**
     * 登录状态标记单独放一个偏好文件，不塞 Keystore —— 那里只放凭据，标记是给界面看的。
     * 独立如意远程 App 是另一个 applicationId，偏好文件天然隔离，读不到宿主的标记。
     */
    private const val PREFS = "ruiyi_remote_sso"
    private const val KEY_STATE = "last_state"

    /** 退出联动写入的状态：设备是随如意 AI 退出主动清掉的，不是登录失败。 */
    private const val STATE_SIGNED_OUT = "signed_out"

    /**
     * 宿主的如意账号变了多少次（登录成功 / 退出各 +1）。
     *
     * 库里的会话是**常驻**的：`RemoteHome` 只在冷启动 `restore()` 一次。宿主换掉本机
     * 设备令牌之后如果没人重读，用户切回「如意」看到的还是上一个账号的界面 —— 登录了
     * 不进、退出了不退。这个计数就是这个「重读一次」的信号，由 [RemoteHome] 订阅。
     *
     * 用同进程的单例而不是宿主回调：如意 AI 和如意在同一个包里、同一个进程，宿主什么都
     * 不用传；独立如意远程 App 从不调用 [signIn]/[signOut]，计数恒为 0，天然不受影响。
     */
    private val _revisions = MutableStateFlow(0)
    val revisions: StateFlow<Int> = _revisions.asStateFlow()

    /**
     * 联动状态（[lastState]）的可观察副本。
     *
     * 界面订阅这个，而不是 `remember { lastState(context) }` 的快照：登录/退出/注册失败
     * 都在同一个进程里改写落盘标记，快照一旦 remember 就永远是首帧那个值 —— 登录失败
     * 写的「连不上中继」在已经组合过的登录页上永远显示不出来。
     */
    private val _state = MutableStateFlow<String?>(null)
    val state: StateFlow<String?> = _state.asStateFlow()

    /**
     * 用如意 AI 登录拿到的账号票注册本机设备。
     *
     * [relayBase] 默认取构建期注入的中继地址（与独立 Remote App 一致）；调用方要指到
     * 别的中继时显式传。
     *
     * 失败时**记下原因再原样抛出**：调用方仍按既有约定兜住异常（如意 AI 模型已经能用，
     * 不能因为远程没注册上就打断登录），而界面靠这个标记告诉用户「远程控制没就位」，
     * 不再只有 logcat 知道。
     */
    suspend fun signIn(
        context: Context,
        accessToken: String,
        relayBase: String = DEFAULT_SERVER_URL,
    ) {
        val account = RemoteAccount(KeystoreSecretStore(context.applicationContext), relayBase)
        try {
            val user = account.loginWithAiServerToken(accessToken)
            clearState(context)
            // 令牌已经落到 Keystore，让常驻的如意会话重读一次，别等重启才认这个登录。
            _revisions.update { it + 1 }
            Log.i(TAG, "远程控制已随如意 AI 登录注册：device=${user.deviceId}")
        } catch (error: Exception) {
            recordState(context, classify(error))
            throw error
        }
    }

    /**
     * 如意 AI 退出如意账号时调用：吊销本机设备并清掉设备令牌。
     *
     * 与 [signIn] 对称 —— 退出联动缺一半的后果不是「少清一点」，而是换号之后仍能连上
     * 上一个账号的电脑。吊销失败不影响本地清理（[RemoteAccount.logout] 内部已兜住），
     * 服务端那条设备记录留到下次登录由 `DEVICE_REVOKED` 分支重建。
     */
    suspend fun signOut(
        context: Context,
        relayBase: String = DEFAULT_SERVER_URL,
        revokeDevice: Boolean = true,
    ) {
        val account = RemoteAccount(KeystoreSecretStore(context.applicationContext), relayBase)
        account.logout(revokeDevice)
        // 落一条「主动退出」而不是清空：如意这一侧没有如意账号登录入口（宿主传了
        // showSso=false），登录页得说清「为什么又要登录、接下来该去哪」。
        recordState(context, STATE_SIGNED_OUT)
        // 设备令牌已删，让常驻的如意会话退回登录页；否则它会继续显示上一个账号的电脑。
        _revisions.update { it + 1 }
        Log.i(TAG, "远程控制已随如意 AI 退出注销：revoke=$revokeDevice")
    }

    /**
     * 上次联动留下的状态：`unreachable` / `rejected` / `unknown`（注册失败分类），
     * 或 `signed_out`（随如意 AI 退出主动清除）；一切正常时为 null。
     *
     * 只给粗分类，不把服务端错误码或原文交给界面 —— 终端用户看不懂，也不该看到内部错误。
     */
    fun lastState(context: Context): String? =
        prefs(context).getString(KEY_STATE, null).also { _state.value = it }

    private fun recordState(context: Context, state: String) {
        prefs(context).edit().putString(KEY_STATE, state).apply()
        _state.value = state
    }

    private fun clearState(context: Context) {
        prefs(context).edit().remove(KEY_STATE).apply()
        _state.value = null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun classify(error: Throwable): String {
        val code = when (error) {
            is RemoteConnectException -> error.code
            is RemoteApiException -> error.code
            else -> null
        }
        return when {
            code == "CONNECTION_FAILED" -> "unreachable"
            code != null -> "rejected"
            error is IOException -> "unreachable"
            else -> "unknown"
        }
    }
}
