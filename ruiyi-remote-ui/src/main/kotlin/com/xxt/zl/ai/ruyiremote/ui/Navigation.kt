package com.xxt.zl.ai.ruyiremote.ui

import com.xxt.zl.ai.ruyiremote.session.SessionSummary

/**
 * 页面层级。
 *
 * 之前所有东西挤在一屏里，是因为「登录 / 电脑 / 连接 / 会话 / 聊天」被当成同一件
 * 事的不同区块。实际上它们是四级递进：**设备 → 电脑 → 会话 → 消息**。
 * 拆成独立的 Screen 之后，每一屏只需要回答一个问题，返回键的行为也随之明确。
 */
sealed interface Screen {
    /** 未登录时的唯一入口。 */
    data object Connect : Screen

    /** 次级登录方式：账号密码 / 如意 SSO。 */
    data object AltSignIn : Screen

    /**
     * 登录后的首页：会话列表。
     *
     * 不再是「我的电脑」选主机——会话（远程任务）才是要落脚的地方。顶部放一个
     * 「主机列表」入口，切换电脑与设置都从那里进。
     */
    data object Home : Screen

    /** 我的电脑列表。 */
    data object Hosts : Screen

    /** 单台电脑：身份、连接状态、进入会话。 */
    data class HostDetail(val hostId: String) : Screen

    /**
     * 一台已经连上的电脑，但还没进任何会话。
     *
     * 会话列表、切主机、新建会话都落在这一屏；用户只有主动点某条会话才进
     * [Chat]。切主机后必须先回到这里，绝不能把旧主机的 Chat 留在栈里——那会让
     * 新连接去重新打开旧主机的 sessionId。
     */
    data class Workspace(val hostId: String) : Screen

    /** 单个会话的对话。 */
    data class Chat(val hostId: String, val summary: SessionSummary) : Screen

    /** 账号、中继地址、后台保活、退出登录。 */
    data object Settings : Screen
}

/**
 * 切主机后的完整返回栈。
 *
 * 刻意清掉旧栈：旧主机的 [Screen.Chat] / [Screen.Workspace] 都绑定着旧连接上的
 * sessionId，中继替换链路后它们已经无法恢复。留下任何一层，返回键都可能把用户
 * 带回「看似还在、实际什么都不能做」的旧页面。新栈只放新主机的
 * [Screen.Workspace]，返回键直接回电脑列表。
 */
internal fun stackAfterHostSwitch(hostId: String): List<Screen> = listOf(Screen.Workspace(hostId))

/**
 * 连接尝试的代号。
 *
 * 中继对同一个 client 只保留一条链路，新连接会让旧链路收到 CONNECTION_REPLACED。
 * 因此一次连接尝试一旦开始，之前所有在飞的 connect / 自动重连回包都必须作废：
 * 它们既不能把旧连接「恢复」回来，也不能在用户已断开、登出或切到别的主机后
 * 覆盖当前状态。这里用单调递增的代号表达这件事，保证判断是同步的，不依赖
 * 协程取消的时序。
 */
internal class ConnectionEpoch {
    private var current = 0L

    /** 开始一次新尝试，返回它的代号；更早的代号从此全部失效。 */
    fun begin(): Long {
        current += 1
        return current
    }

    /** 主动断开 / 登出：让所有在飞回包失效。 */
    fun invalidate() {
        current += 1
    }

    fun isCurrent(token: Long): Boolean = token == current
}
