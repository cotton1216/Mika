package com.xxt.zl.ai.ruyiremote.transport

/**
 * 数据面模式，与 `transport.selected` 的取值一一对应。
 *
 * 对应 Dart `transport/transport.dart` 的 `RemoteTransportMode`。
 */
enum class RemoteTransportMode {
    lan,
    p2p,
    turn,
    relay;

    val wireName: String get() = name
}

/** 尝试更差的数据面的原因（不含任何地址、SDP、异常文本）。 */
enum class RemoteNetworkFallbackReason {
    webrtcUnavailable,
    directFailed,
    directTimeout,
    directRecentlyFailed,
    turnUnavailable,
    turnFailed,
    turnTimeout,
    pathUnverified,
}

/** 一条已授权连接的身份三元组，绑定进 Noise prologue。 */
class RemoteConnectionInfo(
    val connectionId: String,
    val localDeviceId: String,
    val remoteDeviceId: String,
)

/**
 * 传输结束的原因。[code] 用协议的错误词汇表（`CONNECTION_FAILED`、
 * `CONNECTION_REPLACED`、`AUTH_INVALID`、`TOKEN_EXPIRED`、`HOST_OFFLINE`、
 * `SECURE_CHANNEL_FAILED`…）。
 */
class RemoteTransportClosed(
    val code: String,
    val message: String,
    val retryable: Boolean = true,
    /** Server 关闭 Control socket 时的 WebSocket close code。 */
    val closeCode: Int? = null,
) {
    override fun toString(): String =
        "RemoteTransportClosed($code${closeCode?.let { " ws=$it" } ?: ""}): $message"
}

/** 建链阶段抛出的异常（Control 握手、connect 请求）。[code] 同 [RemoteTransportClosed]。 */
class RemoteTransportException(
    val code: String,
    message: String,
    val retryable: Boolean = true,
    val closeCode: Int? = null,
) : Exception(message) {
    override fun toString(): String = "RemoteTransportException($code): ${message}"
}

/**
 * 一条已授权 Host 连接的数据面 + 握手中转。
 *
 * 实现：
 * * [RelayTransport] —— `relay` 帧里的密文，走 Control socket 或 Server 分派的
 *   Relay 节点数据 socket；
 * * WebRTC（后续）—— LAN / P2P / TURN，DataChannel `dsh`（`ordered: true`）传
 *   二进制密文。
 *
 * 两者实现同一接口，所以安全通道、RPC 层与 carrier 不需要区分。
 */
interface SecureHandshakeTransport {
    val mode: RemoteTransportMode

    val info: RemoteConnectionInfo

    /**
     * 注册回调。注册前已经到达的帧会**按序立即补发**。
     * [onClosed] 只触发一次，除非先调了 [close]。
     */
    fun listen(
        onData: (ciphertext: ByteArray, counter: Long?) -> Unit,
        onHandshake: (step: Int, data: ByteArray) -> Unit,
        onClosed: (RemoteTransportClosed) -> Unit,
    )

    /** 发送 Noise 握手消息 [step]（客户端从 1 开始）。 */
    suspend fun sendHandshake(step: Int, data: ByteArray)

    /**
     * 发送一条 Noise 传输消息。[counter] 是加密它时用的 Noise nonce——
     * Relay 帧会带上它，Host 要求与自己的接收 nonce 一致。
     */
    suspend fun send(ciphertext: ByteArray, counter: Long)

    suspend fun close()
}
