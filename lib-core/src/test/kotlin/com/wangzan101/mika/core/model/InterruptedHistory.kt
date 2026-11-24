package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.agent.AgentLoop

/**
 * 插话打断流式回复之后发给模型的历史(见 AgentLoop.keepInterrupted):用户的话,
 * 被打断的那段只有文字的回复(句子可能断在半截,不带思考和调用),接着插话和运行时状态两条用户消息。
 * 各服务商的适配器都要把它原样转成合法的请求。
 */
internal object InterruptedHistory {
    const val ASK = "详细介绍一下光合作用"
    const val PARTIAL = "光合作用是绿色植物利用光能,把二氧化碳和水"
    val STEER = "${AgentLoop.STEER_MARKER}\n简短点"
    const val RUNTIME = "Current place: no screen or page read yet"

    val messages = listOf(
        Message(Role.User, listOf(Part.Text(ASK)), Author.User),
        Message(Role.Assistant, listOf(Part.Text(PARTIAL)), Author.Mika),
        Message(Role.User, listOf(Part.Text(STEER)), Author.User),
        Message(Role.User, listOf(Part.Text(RUNTIME)), Author.Mika),
    )
}
