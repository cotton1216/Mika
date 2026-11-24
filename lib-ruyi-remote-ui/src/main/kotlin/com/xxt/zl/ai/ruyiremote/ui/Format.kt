package com.xxt.zl.ai.ruyiremote.ui

import com.xxt.zl.ai.ruyiremote.account.HostTrust
import com.xxt.zl.ai.ruyiremote.auth.SignInFailure
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 信任状态 → 人话。三档颜色由调用方按 [HostTrust] 决定。 */
fun trustLine(trust: HostTrust): String = when (trust) {
    HostTrust.pinned -> "身份已核对"
    HostTrust.unpinned -> "首次连接，会记住这台电脑"
    HostTrust.keyChanged -> "身份变了，已拒绝连接"
    HostTrust.unknown -> "暂时拿不到这台电脑的身份"
}

/** 信任状态的补充说明，只在详情页展示（列表里太啰嗦）。 */
fun trustDetail(trust: HostTrust): String = when (trust) {
    HostTrust.pinned -> "这台电脑的身份密钥与你第一次连接时记住的一致。"
    HostTrust.unpinned -> "这次连接成功后会记住它的身份，下次若变化会拒绝连接。"
    HostTrust.keyChanged -> "密钥与记住的不一致。确认是本人重装过系统，再点「重新信任」。"
    HostTrust.unknown -> "还没拿到它的身份密钥，连一次就有了。"
}

/** IAM 登录失败 → 人话。服务端原文不展示。 */
fun describeSignIn(failure: SignInFailure, code: String? = null): String = when {
    // 中继没开如意账号登录：这不是「登录失败」，重试也没用，必须说清是配置问题。
    code == "relay_not_configured" -> "这套中继未开启如意账号登录，请改用配对码或账号密码。"
    failure == SignInFailure.Rejected -> "登录被拒绝，请重新发起。"
    failure == SignInFailure.Denied -> "你在授权页拒绝了登录。"
    failure == SignInFailure.Expired -> "授权码已失效，请重新登录。"
    failure == SignInFailure.Network -> "网络不通或服务暂时不可用，请稍后重试。"
    else -> "等待浏览器返回超时。"
}

private val timeOfDay = SimpleDateFormat("HH:mm", Locale.getDefault())
private val monthDay = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
private val fullDate = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

/**
 * 会话列表的时间：今天只给时分，今年给月日，更早给年份。
 * 一律用「几天前」这种相对说法反而更难核对，所以保留具体时刻。
 */
fun formatWhen(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = epochMs }
    return when {
        now.get(Calendar.YEAR) != then.get(Calendar.YEAR) -> fullDate.format(Date(epochMs))
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> timeOfDay.format(Date(epochMs))
        else -> monthDay.format(Date(epochMs))
    }
}

/** 指纹按 4 位分组，和桌面端显示的那串对齐，方便逐段核对。 */
fun groupFingerprint(raw: String): String =
    raw.replace(" ", "").uppercase().chunked(4).joinToString(" ")
