package com.xxt.zl.ai.ruyiremote.ui

/**
 * 是否把「某个网络丢失」判成整条通道已死。
 *
 * 抽成不依赖 Android 的纯函数，是为了让两个误判来源能被单测钉住：
 * - 丢的不是当前承载网络（VPN、蜂窝这类旁支消失）：不该拆掉正在用的连接。
 * - 系统还有别的 INTERNET 网络：Wi-Fi → 蜂窝切换会先收到 Wi-Fi 的 onLost，
 *   此时连接可能自己迁移过去，交给 watchdog 判断比立刻判死更稳。
 *
 * 只有「承载网络没了且无替代」才走亚秒判死；否则保留原来的心跳兜底。
 */
internal fun shouldJudgeNetworkLost(
    lostIsActive: Boolean,
    hasAlternativeInternet: Boolean,
): Boolean = lostIsActive && !hasAlternativeInternet
