package com.xxt.zl.ai.ruyiremote.channel

/**
 * [SecureChannel] 的下游只需要这三件事。
 *
 * 抽成接口的唯一目的是让 RPC 层能在单测里换成假管道——Dart 侧靠 Dart 的隐式接口
 * 天然具备这个能力，Kotlin 必须显式声明。
 */
interface SecureMessagePipe {
    /** 注册接收方；此前已解出的报文按序补发。[onClosed] 只触发一次。 */
    fun listen(onMessage: (message: ByteArray) -> Unit, onClosed: (SecureChannelClosed) -> Unit)

    /** 发一条 Remote 报文。调用之间串行化；失败会关闭管道。 */
    suspend fun send(message: ByteArray, canSend: (() -> Boolean)? = null)

    /** 关闭通道；[reason] 用来保留本地失败原因而不是报成客户端主动断开。 */
    suspend fun close(reason: SecureChannelClosed? = null)
}
