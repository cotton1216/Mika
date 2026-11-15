package com.xxt.zl.ai.ruyiremote.ui

import android.content.Context
import android.util.Log
import com.xxt.zl.ai.ruyiremote.account.KeystoreSecretStore
import com.xxt.zl.ai.ruyiremote.account.RemoteAccount
import com.xxt.zl.ai.ruyiremote.net.DEFAULT_SERVER_URL

/**
 * 把「如愿 App 的那一次登录」接到远程控制上。
 *
 * 为什么不是再登录一次：AI-Server 的授权码**一次性、60 秒过期**，同一个码换不出两份票。
 * 如愿登录时码已经换成了 AI-Server 的 `access_token`，所以远程控制只能复用这张票 ——
 * 由中继把它换成账号会话，再把本机注册成 client 设备。中继读的是同一个 AI-Server
 * profile（`iamFromToken`），因此和远程自己走一遍授权落到**同一个账号主键**。
 *
 * 注册在本机落的是**设备令牌**（[RemoteAccount] 存进 Keystore），账号侧那张短时效票据
 * 用完即弃：之后 RemoteHome 的 `restore()` 直接进「我的电脑」，不会再问登录。
 */
object RemoteSso {
    private const val TAG = "RemoteSso"

    /**
     * 用如愿登录拿到的账号票注册本机设备。
     *
     * [relayBase] 默认取构建期注入的中继地址（与独立 Remote App 一致）；调用方要指到
     * 别的中继时显式传。
     */
    suspend fun signIn(
        context: Context,
        accessToken: String,
        relayBase: String = DEFAULT_SERVER_URL,
    ) {
        val account = RemoteAccount(KeystoreSecretStore(context.applicationContext), relayBase)
        val user = account.loginWithAiServerToken(accessToken)
        Log.i(TAG, "远程控制已随如愿登录注册：device=${user.deviceId}")
    }
}
