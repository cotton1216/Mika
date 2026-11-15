package com.xxt.zl.ai.ruyiremote.transport

import com.xxt.zl.ai.ruyiremote.protocol.ControlFrame
import com.xxt.zl.ai.ruyiremote.protocol.ControlFrameLimits
import com.xxt.zl.ai.ruyiremote.protocol.PROTOCOL_VERSION
import com.xxt.zl.ai.ruyiremote.protocol.ProtocolError
import com.xxt.zl.ai.ruyiremote.protocol.acceptNegotiatedCapabilities
import com.xxt.zl.ai.ruyiremote.protocol.boolean
import com.xxt.zl.ai.ruyiremote.protocol.decodeControlFrame
import com.xxt.zl.ai.ruyiremote.protocol.decodeRelayDataFrame
import com.xxt.zl.ai.ruyiremote.protocol.encodeControlFrame
import com.xxt.zl.ai.ruyiremote.protocol.encodeRelayDataFrame
import com.xxt.zl.ai.ruyiremote.protocol.int
import com.xxt.zl.ai.ruyiremote.protocol.str
import com.xxt.zl.ai.ruyiremote.util.fromBase64Url
import com.xxt.zl.ai.ruyiremote.util.intValue
import com.xxt.zl.ai.ruyiremote.util.toBase64Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 诊断钩子：每条进出的 Control 帧，以及 Relay 数据帧记为 `data-in` / `data-out`
 * （token、ticket、密文、SDP 一律脱敏）。
 */
typealias ControlTracer = (direction: String, frame: JsonObject) -> Unit

/**
 * Relay 传输：`hello` → `hello.ack` → `connect.request` → `connect.accepted`，
 * 然后 Noise 握手与密文走 `relay` 帧。应答 Server 的 `ping`；连续 3 个心跳周期
 * 没有收到任何帧就判定链路已死。
 *
 * `transport.relay.attach`：当 `connect.accepted` 带 Relay 分派（`relayEndpoint`、
 * `relayTicket`、`relayNodeId`、`relayEpoch`）时，客户端打开那条数据 WebSocket，
 * 带 ticket 发 `relay.attach`（ticket **绝不**走 Control socket），等 `relay.ready`，
 * 再宣告 `transport.selected`（`relay`）。Noise 握手始终留在 Control socket 上；
 * `relay` 帧与节点心跳移到数据 socket。`relay.detach` 或数据 socket 断开都会终止
 * 连接：Noise 状态绝不跨连接复用，由调用方重新建一条。没有分派（老 Server 或
 * 老 Host）时一切留在 Control socket 上。
 *
 * 直连路径（`offerDirect`）：客户端额外提供 `transport.lan`、`transport.p2p`、
 * `transport.turn`。Server 协商了其中任一条（且没有 `webrtcEnabled: false`）时，
 * `connect.request` 会把它们排在 `relay` 前面，[connect] 在 `connect.accepted`
 * 处返回而数据面仍未定型：[directTransports] 非空、没有 attach、也没有发
 * `transport.selected`。之后由调用方经 [sendSignal] / [onSignal] 协商 WebRTC。
 *
 * 一个设备一条 Control WebSocket：Server 会关掉同设备的旧 socket
 * （`CONNECTION_REPLACED`，close code 4003），新的 `connect.request` 会替换该设备
 * 之前的 Host 连接。
 *
 * ## 与 Dart 版的两处实现差异（都不影响线上行为）
 *
 * 1. **并发模型**。Dart 单 isolate；这里用一把可重入的 Java 监视器锁串行化全部
 *    状态，回调也在锁内同步触发。所以 `onData` / `onHandshake` / `onClosed`
 *    **必须快速返回**，且允许在其中直接调 [send] / [sendHandshake]（同线程可重入）。
 *    这与上游「慢消费者」语义一致：消费慢就等于拖慢读帧。
 * 2. **`close()` 不等对端确认**。Dart 的 `await socket.close()` 等的是关闭握手完成；
 *    OkHttp 的 `WebSocket.close()` 只是发出关闭帧，这里直接返回，对端回执走
 *    [ControlSocketListener.onClosed]。
 */
class RelayTransport private constructor(
    private val socket: ControlSocket,
    private val deviceId: String,
    private val hostDeviceId: String,
    private val tracer: ControlTracer?,
    private val connector: ControlSocketConnector,
    private val timeoutMs: Long,
    private val offered: List<String>,
    private val preferredTransports: List<String>?,
) : SecureHandshakeTransport {

    private val lock = Any()

    private var limits = ControlFrameLimits()
    private var connectionId: String? = null
    private var direct: List<String> = emptyList()
    private var webrtcFallbackMs: Int? = null

    /** Relay 节点数据 socket（分派后才有）。 */
    private var data: ControlSocket? = null
    private var relayEpoch: Long? = null

    /** 首次握手（直到 `connect.accepted` 或 attach 完成）的等待点。 */
    private var ready: CompletableDeferred<Unit>? = null

    private var heartbeatMs = DEFAULT_HEARTBEAT_MS
    private var lastReceivedNanos = System.nanoTime()
    private var watchdog: ScheduledFuture<*>? = null

    private var closed = false
    private var closedNotified = false
    private var socketCloseCode: Int? = null
    private var socketCloseReason: String? = null

    private var onDataHandler: ((ByteArray, Long?) -> Unit)? = null
    private var onHandshakeHandler: ((Int, ByteArray) -> Unit)? = null
    private var onClosedHandler: ((RemoteTransportClosed) -> Unit)? = null

    /** 注册回调之前到达的帧，按序暂存。 */
    private val backlog = mutableListOf<() -> Unit>()
    private var pendingClose: RemoteTransportClosed? = null

    /** 协商后的 `hello.ack` 心跳间隔。 */
    val heartbeatIntervalMs: Int get() = synchronized(lock) { heartbeatMs }

    /** 双方协商出的直连路径，按配置偏好排序；走 Relay 时为空。 */
    val directTransports: List<String> get() = synchronized(lock) { direct }

    /** `hello.ack.webrtcFallbackTimeoutMs`，Server 没给时为 null。 */
    val webrtcFallbackTimeoutMs: Int? get() = synchronized(lock) { webrtcFallbackMs }

    /** 接收本连接的 `signal.answer`、`signal.ice`、`transport.selected`（WebRTC 协商）。 */
    @Volatile
    var onSignal: ((ControlFrame) -> Unit)? = null

    override val mode: RemoteTransportMode get() = RemoteTransportMode.relay

    override val info: RemoteConnectionInfo
        get() = synchronized(lock) {
            val id = connectionId ?: error("relay connection has not been authorized")
            RemoteConnectionInfo(connectionId = id, localDeviceId = deviceId, remoteDeviceId = hostDeviceId)
        }

    // -----------------------------------------------------------------------
    // 入站

    private fun onText(text: String) {
        synchronized(lock) {
            if (closed) return
            lastReceivedNanos = System.nanoTime()
            val frame = try {
                decodeControlFrame(text, limits)
            } catch (error: ProtocolError) {
                // 本客户端解不了的帧（未知类型、字段不对、更新的协议版本、超长）
                // 每次重连都会同样失败：直接停，而不是无限重连。重试交给人来决定。
                // PROTOCOL_MISMATCH 用来跟「REST 回复不是 JSON」（强制门户或代理）
                // 区分开——两者都是 INVALID_MESSAGE。
                fail(RemoteTransportClosed(mismatchCode(error), error.message.orEmpty(), retryable = false))
                return
            }
            trace("in", frame.toJson())
            try {
                handle(frame)
            } catch (error: ProtocolError) {
                // 格式正确但时机不对的帧（重连竞态）值得再试一次；被拒的能力或版本不值得。
                val code = error.code ?: "INVALID_MESSAGE"
                fail(RemoteTransportClosed(code, error.message.orEmpty(), retryable = code !in INCOMPATIBLE_CODES))
            }
        }
    }

    private fun handle(frame: ControlFrame) {
        val p = frame.payload
        when (frame.type) {
            "hello.ack" -> handleHelloAck(p)

            "connect.accepted" -> {
                val deferred = ready
                if (deferred == null || deferred.isCompleted || connectionId != null) {
                    throw ProtocolError("Unexpected connect.accepted.")
                }
                connectionId = p.str("connectionId")
                startWatchdog()
                val endpoint = p.str("relayEndpoint")
                val ticket = p.str("relayTicket")
                // 直连尝试自己挑数据面；失败的直连会换成新连接重来，所以这条不 attach。
                if (endpoint == null || ticket == null || direct.isNotEmpty()) {
                    deferred.complete(Unit)
                    return
                }
                relayEpoch = p.intValue("relayEpoch")
                attach(endpoint, ticket)
            }

            "connect.rejected" -> {
                if (connectionId != null && p.str("connectionId") != connectionId) return
                val code = p.str("code") ?: "CONNECTION_REJECTED"
                fail(
                    RemoteTransportClosed(
                        code,
                        p.str("message") ?: "The Host rejected the connection.",
                        // Host 睡眠时 Server 会用 HOST_OFFLINE 拒：继续退避重连，
                        // 等 Host 回来。
                        retryable = code in RETRYABLE_CODES,
                    ),
                )
            }

            "error" -> {
                val frameConnectionId = p.str("connectionId")
                if (frameConnectionId != null && connectionId != null && frameConnectionId != connectionId) return
                val code = p.str("code") ?: throw ProtocolError("error.code is required.")
                val message = p.str("message") ?: throw ProtocolError("error.message is required.")
                fail(
                    RemoteTransportClosed(
                        code,
                        message,
                        retryable = p.boolean("retryable") == true || code in RETRYABLE_CODES,
                    ),
                )
            }

            "relay" -> {
                // 节点分派之后，密文归数据 socket。
                if (data != null) return
                receiveRelay(p)
            }

            "secure.handshake" -> receiveHandshake(p)

            "signal.answer", "signal.ice", "transport.selected" -> {
                if (p.str("connectionId") == connectionId && p.str("targetDeviceId") == deviceId) {
                    onSignal?.invoke(frame)
                }
            }

            "ping" -> {
                val nonce = p.str("nonce") ?: throw ProtocolError("ping.nonce is required.")
                sendControl("pong", buildJsonObject { put("nonce", nonce) })
            }

            // pong、offer 与 Host 专用帧都不是给客户端的。
            else -> Unit
        }
    }

    private fun handleHelloAck(p: JsonObject) {
        val deferred = ready
        if (deferred == null || deferred.isCompleted) {
            throw ProtocolError("Unexpected hello.ack.")
        }
        val negotiated = try {
            acceptNegotiatedCapabilities(offered, stringList(p, "capabilities"))
        } catch (error: ProtocolError) {
            throw ProtocolError(error.message.orEmpty(), "CAPABILITY_NOT_SUPPORTED")
        }
        if (RELAY_CAPABILITY !in negotiated) {
            throw ProtocolError(
                "Server did not negotiate the required Relay capability.",
                "CAPABILITY_NOT_SUPPORTED",
            )
        }
        limits = ControlFrameLimits(
            maxControlFrameBytes = p.int("maxControlFrameBytes"),
            maxRelayFrameBytes = p.int("maxRelayFrameBytes"),
        )
        heartbeatMs = p.int("heartbeatIntervalMs") ?: heartbeatMs
        if (p.boolean("webrtcEnabled") != false) {
            val preference = preferredTransports ?: DEFAULT_DIRECT_PREFERENCE
            direct = preference.filter { it != "relay" && "transport.$it" in negotiated }
            webrtcFallbackMs = p.int("webrtcFallbackTimeoutMs")
        }
        sendControl(
            "connect.request",
            buildJsonObject {
                put("hostDeviceId", hostDeviceId)
                putJsonArray("preferredTransports") {
                    (direct + "relay").forEach { add(JsonPrimitive(it)) }
                }
            },
        )
    }

    private fun receiveRelay(p: JsonObject) {
        if (p.str("connectionId") != connectionId || p.str("targetDeviceId") != deviceId) {
            throw ProtocolError("Received a relay frame for an unknown connection.")
        }
        val ciphertext = fromBase64Url(p.str("ciphertext") ?: throw ProtocolError("relay.ciphertext is required."))
        val counter = p.intValue("counter") ?: throw ProtocolError("relay.counter is required.")
        deliver { onDataHandler?.invoke(ciphertext, counter) }
    }

    private fun receiveHandshake(p: JsonObject) {
        if (p.str("connectionId") != connectionId || p.str("targetDeviceId") != deviceId) {
            throw ProtocolError("Received a secure handshake frame for an unknown connection.")
        }
        val payload = fromBase64Url(p.str("data") ?: throw ProtocolError("secure.handshake.data is required."))
        val step = p.int("step") ?: throw ProtocolError("secure.handshake.step is required.")
        deliver { onHandshakeHandler?.invoke(step, payload) }
    }

    // -----------------------------------------------------------------------
    // Relay 节点数据 socket

    /**
     * 打开数据 socket 并 attach。注意这里**不能**在 `connect()` 的等待路径上阻塞：
     * `connect.accepted` 之后 `ready` 由 `relay.ready` 完成，中间没有任何人等在
     * 这条协程上——`attach` 从帧处理里同步发起，因此 socket 建链用回调接续。
     */
    private fun attach(endpoint: String, ticket: String) {
        val listener = DataSocketListener()
        // 建链是异步的：这里起一条独立协程，不占用帧处理路径。
        RelayScope.launch {
            val socket = try {
                connector.connect(endpoint, timeoutMs, listener)
            } catch (error: ControlSocketException) {
                fail(RemoteTransportClosed("CONNECTION_FAILED", "Relay data socket failed: ${error.message}"))
                return@launch
            } catch (error: Throwable) {
                fail(RemoteTransportClosed("CONNECTION_FAILED", "Relay data socket failed: ${error.message}"))
                return@launch
            }
            val attached = synchronized(lock) {
                if (closed) {
                    false
                } else {
                    data = socket
                    sendData(
                        "relay.attach",
                        buildJsonObject {
                            put("connectionId", connectionId.orEmpty())
                            put("deviceId", deviceId)
                            put("peerDeviceId", hostDeviceId)
                            put("ticket", ticket)
                        },
                    )
                    true
                }
            }
            if (!attached) socket.close(1000, "")
        }
    }

    private inner class DataSocketListener : ControlSocketListener {
        override fun onText(text: String) {
            synchronized(lock) {
                if (closed) return
                lastReceivedNanos = System.nanoTime()
                val frame = try {
                    decodeRelayDataFrame(text, limits)
                } catch (error: ProtocolError) {
                    fail(RemoteTransportClosed(mismatchCode(error), error.message.orEmpty(), retryable = false))
                    return
                }
                trace("data-in", frame.toJson())
                try {
                    handleData(frame)
                } catch (error: ProtocolError) {
                    val code = error.code ?: "INVALID_MESSAGE"
                    fail(RemoteTransportClosed(code, error.message.orEmpty(), retryable = code !in INCOMPATIBLE_CODES))
                }
            }
        }

        override fun onBinary(bytes: ByteArray) {
            synchronized(lock) {
                if (closed) return
                fail(
                    RemoteTransportClosed(
                        "PROTOCOL_MISMATCH",
                        "Relay data frames must be text JSON.",
                        retryable = false,
                    ),
                )
            }
        }

        override fun onClosed(code: Int?, reason: String?) {
            synchronized(lock) {
                fail(
                    RemoteTransportClosed(
                        "CONNECTION_FAILED",
                        "Relay data socket closed (${code ?: "no code"} ${reason.orEmpty()})".trim(),
                        closeCode = code,
                    ),
                )
            }
        }

        override fun onFailure(error: Throwable) {
            synchronized(lock) {
                fail(RemoteTransportClosed("CONNECTION_FAILED", "Relay data socket error: ${error.message}"))
            }
        }
    }

    private fun handleData(frame: ControlFrame) {
        val p = frame.payload
        when (frame.type) {
            "relay.ready" -> {
                val deferred = ready ?: return
                if (deferred.isCompleted) return
                if (p.str("connectionId") != connectionId ||
                    (relayEpoch != null && p.intValue("epoch") != relayEpoch)
                ) {
                    throw ProtocolError("Relay data attach does not match the assigned connection.")
                }
                // 对齐上游 AdaptiveTransport：带分派的 Host 会压住 Noise 握手等客户端
                // 确认数据面，然后自己 attach。
                sendControl(
                    "transport.selected",
                    buildJsonObject {
                        put("connectionId", connectionId.orEmpty())
                        put("targetDeviceId", hostDeviceId)
                        put("transport", "relay")
                    },
                )
                deferred.complete(Unit)
            }

            "relay.detach" ->
                fail(RemoteTransportClosed("CONNECTION_FAILED", "The Host detached from the Relay node."))

            "ping" -> sendData("pong", p)

            "relay" -> receiveRelay(p)

            "secure.handshake" -> receiveHandshake(p)

            else -> Unit
        }
    }

    /**
     * 数据 socket 的帧用**同一套信封**，但 `relay.ready` / `relay.detach` / `ping`
     * 不在 Control 的 15 种类型白名单里，所以这里直接用已由
     * [decodeRelayDataFrame] 校验过的 payload，不再过一遍 Control 类型表。
     */
    private fun sendData(type: String, payload: JsonObject) {
        val frame = ControlFrame(type, payload)
        val encoded = encodeRelayDataFrame(frame, limits)
        trace("data-out", frame.toJson())
        val socket = data
            ?: throw RemoteTransportException("CONNECTION_FAILED", "The relay data socket is not open.")
        socket.send(encoded)
    }

    // -----------------------------------------------------------------------
    // 出站 / 回调

    override suspend fun sendHandshake(step: Int, data: ByteArray) {
        synchronized(lock) {
            val id = requireConnection()
            sendControl(
                "secure.handshake",
                buildJsonObject {
                    put("connectionId", id)
                    put("targetDeviceId", hostDeviceId)
                    put("step", step)
                    put("data", toBase64Url(data))
                },
            )
        }
    }

    /** 发一条本连接的 WebRTC 信令帧（`signal.offer`、`signal.ice`、`transport.selected`）。 */
    suspend fun sendSignal(type: String, payload: JsonObject) {
        synchronized(lock) {
            val body = buildJsonObject {
                put("connectionId", requireConnection())
                put("targetDeviceId", hostDeviceId)
                for ((key, value) in payload) put(key, value)
            }
            sendControl(type, body)
        }
    }

    override suspend fun send(ciphertext: ByteArray, counter: Long) {
        synchronized(lock) {
            val payload = buildJsonObject {
                put("connectionId", requireConnection())
                put("targetDeviceId", hostDeviceId)
                put("counter", counter)
                put("ciphertext", toBase64Url(ciphertext))
            }
            if (data != null) sendData("relay", payload) else sendControl("relay", payload)
        }
    }

    override fun listen(
        onData: (ciphertext: ByteArray, counter: Long?) -> Unit,
        onHandshake: (step: Int, data: ByteArray) -> Unit,
        onClosed: (RemoteTransportClosed) -> Unit,
    ) {
        synchronized(lock) {
            onDataHandler = onData
            onHandshakeHandler = onHandshake
            onClosedHandler = onClosed
            val pending = backlog.toList()
            backlog.clear()
            for (action in pending) action()
            val closing = pendingClose
            if (closing != null && !closedNotified) {
                closedNotified = true
                onClosed(closing)
            }
        }
    }

    private fun requireConnection(): String {
        val id = connectionId
        if (closed || id == null) {
            throw RemoteTransportException("CONNECTION_FAILED", "The relay connection is not open.")
        }
        return id
    }

    private fun sendControl(type: String, payload: JsonObject) {
        if (closed) {
            throw RemoteTransportException("CONNECTION_FAILED", "The relay control socket is closed.")
        }
        val frame = ControlFrame(type, payload)
        val encoded = encodeControlFrame(frame, limits)
        trace("out", frame.toJson())
        socket.send(encoded)
    }

    private fun deliver(action: () -> Unit) {
        if (onDataHandler == null) backlog.add(action) else action()
    }

    private fun trace(direction: String, frame: JsonObject) {
        val tracer = tracer ?: return
        val payload = frame["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val redacted = buildJsonObject {
            for ((key, value) in frame) {
                if (key != "payload") put(key, value)
            }
            put(
                "payload",
                buildJsonObject {
                    for ((key, value) in payload) {
                        when (key) {
                            "accessToken", "relayTicket", "ticket" -> put(key, "<redacted>")
                            // 密文、SDP、candidate 都只报长度：SDP 会暴露设备网络地址。
                            "ciphertext", "sdp" -> put(key, "<${(value as? JsonPrimitive)?.content?.length ?: 0} chars>")
                            "candidate" -> put(key, "<redacted>")
                            else -> put(key, value)
                        }
                    }
                },
            )
        }
        tracer(direction, redacted)
    }

    // -----------------------------------------------------------------------
    // 心跳 / 收尾

    private fun startWatchdog() {
        watchdog?.cancel(false)
        val period = heartbeatMs.toLong()
        watchdog = scheduler.scheduleAtFixedRate(
            {
                synchronized(lock) {
                    if (!closed) {
                        val silenceNanos = System.nanoTime() - lastReceivedNanos
                        if (silenceNanos > period * 3 * NANOS_PER_MILLI) {
                            fail(RemoteTransportClosed("CONNECTION_FAILED", "The Server Control channel went silent."))
                            socket.close(4000, "heartbeat timeout")
                        }
                    }
                }
            },
            period,
            period,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun fail(reason: RemoteTransportClosed) {
        if (closed) return
        closed = true
        watchdog?.cancel(false)
        watchdog = null
        val deferred = ready
        if (deferred != null && !deferred.isCompleted) {
            deferred.completeExceptionally(
                RemoteTransportException(reason.code, reason.message, reason.retryable, reason.closeCode),
            )
        } else if (onClosedHandler != null) {
            if (!closedNotified) {
                closedNotified = true
                onClosedHandler?.invoke(reason)
            }
        } else {
            pendingClose = reason
        }
        data?.close(1000, "")
        data = null
        socket.close(1000, "")
    }

    private fun abort() {
        synchronized(lock) {
            closed = true
            watchdog?.cancel(false)
            watchdog = null
            data?.close(1000, "")
            data = null
        }
        socket.close(1000, "")
    }

    override suspend fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            closedNotified = true
            watchdog?.cancel(false)
            watchdog = null
        }
        data?.close(1000, "client closed")
        socket.close(1000, "client closed")
    }

    // -----------------------------------------------------------------------
    // 建链

    private fun sendHello(accessToken: String, clientVersion: String?) {
        sendControl(
            "hello",
            buildJsonObject {
                put("role", "client")
                put("deviceId", deviceId)
                put("accessToken", accessToken)
                putJsonArray("protocols") { add(JsonPrimitive(PROTOCOL_VERSION)) }
                putJsonArray("capabilities") { offered.forEach { add(JsonPrimitive(it)) } }
                if (clientVersion != null) put("clientVersion", clientVersion)
            },
        )
    }

    private fun closeReason(): RemoteTransportClosed {
        val code = socketCloseCode
        val reason = socketCloseReason.orEmpty()
        val known = reason.takeIf { CLOSE_REASON_PATTERN.matches(it) }
        if (code == 4003) {
            return RemoteTransportClosed(
                "CONNECTION_REPLACED",
                "Another connection of this device replaced the Control channel.",
                retryable = false,
                closeCode = code,
            )
        }
        return RemoteTransportClosed(
            known ?: "CONNECTION_FAILED",
            "Control channel closed (${code?.toString() ?: "no code"} $reason)".trim(),
            retryable = known == null || known in RETRYABLE_CODES,
            closeCode = code,
        )
    }

    /** Control socket 上的回调。由 [connect] 通过 [ForwardingListener] 转发进来。 */
    internal fun handleSocketText(text: String) = onText(text)

    internal fun handleSocketBinary() {
        synchronized(lock) {
            if (closed) return
            fail(RemoteTransportClosed("PROTOCOL_MISMATCH", "Relay control frames must be text JSON.", retryable = false))
        }
    }

    internal fun handleSocketClosed(code: Int?, reason: String?) {
        synchronized(lock) {
            socketCloseCode = code
            socketCloseReason = reason
            fail(closeReason())
        }
    }

    internal fun handleSocketFailure(error: Throwable) {
        synchronized(lock) {
            fail(RemoteTransportClosed("CONNECTION_FAILED", "Control socket error: ${error.message}"))
        }
    }

    companion object {
        private const val RELAY_CAPABILITY = "transport.relay"
        private const val DEFAULT_HEARTBEAT_MS = 25_000
        private const val NANOS_PER_MILLI = 1_000_000L

        private val RELAY_OFFERED = listOf("transport.relay", "transport.relay.attach")

        private val DIRECT_OFFERED = listOf(
            "transport.lan",
            "transport.p2p",
            "transport.turn",
            "transport.relay",
            "transport.relay.attach",
        )

        private val DEFAULT_DIRECT_PREFERENCE = listOf("lan", "p2p", "turn")

        private val CLOSE_REASON_PATTERN = Regex("^[A-Z][A-Z0-9_]+$")

        private val RETRYABLE_CODES = setOf(
            "CONNECTION_FAILED",
            "HOST_OFFLINE",
            "RATE_LIMITED",
            "SLOW_CONSUMER",
            "RELAY_UNAVAILABLE",
            "TOKEN_EXPIRED",
            "AUTH_INVALID",
        )

        /** 说明两侧说的不是同一套协议的 [ProtocolError] code。 */
        private val INCOMPATIBLE_CODES = setOf(
            "UNSUPPORTED_VERSION",
            "CAPABILITY_NOT_SUPPORTED",
            "FRAME_TOO_LARGE",
        )

        private val scheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "dsh-relay-watchdog").apply { isDaemon = true }
            }

        /** 未注入 connector 时用的默认实现（OkHttp 共享连接池）。 */
        @Volatile
        var defaultConnector: ControlSocketConnector = OkHttpControlSocketConnector(sharedHttpClient)
            private set

        /**
         * attach（打开 Relay 数据 socket）用的调度器。
         *
         * 生产用 IO；单测换成 `Dispatchers.Unconfined` 才能让「喂帧 → attach → 断言」
         * 在同一条线程上确定性地跑完。
         */
        @Volatile
        internal var attachDispatcher: CoroutineDispatcher = Dispatchers.IO

        /** 仅供测试替换默认 connector。 */
        fun useConnector(connector: ControlSocketConnector) {
            defaultConnector = connector
        }

        /**
         * 建立一条 Control 连接并等到 `connect.accepted`。
         *
         * [url] 形如 `wss://<server>/ws/v1/connect`；[offerDirect] 为 true 时同时
         * 提供 LAN/P2P/TURN 能力（数据面随后由 WebRTC 接手，[directTransports] 非空）。
         */
        suspend fun connect(
            url: String,
            deviceId: String,
            accessToken: String,
            hostDeviceId: String,
            clientVersion: String? = null,
            timeoutMs: Long = 10_000,
            connector: ControlSocketConnector = defaultConnector,
            tracer: ControlTracer? = null,
            offerDirect: Boolean = false,
            preferredTransports: List<String>? = null,
        ): RelayTransport {
            // socket 在 transport 存在之前就要挂监听（OkHttp 从 newWebSocket 起就可能回调），
            // 所以先给一个转发壳，等 transport 建好再指过去。
            val forward = ForwardingListener()
            val socket = try {
                connector.connect(url, timeoutMs, forward)
            } catch (error: ControlSocketException) {
                val status = error.httpStatusCode
                throw RemoteTransportException(
                    if (status == 401 || status == 403) "AUTH_INVALID" else "CONNECTION_FAILED",
                    "Control WebSocket failed: ${error.message}${status?.let { " (HTTP $it)" } ?: ""}",
                )
            } catch (error: TimeoutCancellationException) {
                throw RemoteTransportException("CONNECTION_FAILED", "Control WebSocket connect timed out.")
            } catch (error: Throwable) {
                throw RemoteTransportException(
                    "CONNECTION_FAILED",
                    "Control WebSocket connect failed: ${error.message}",
                )
            }

            val transport = RelayTransport(
                socket = socket,
                deviceId = deviceId,
                hostDeviceId = hostDeviceId,
                tracer = tracer,
                connector = connector,
                timeoutMs = timeoutMs,
                offered = if (offerDirect) DIRECT_OFFERED else RELAY_OFFERED,
                preferredTransports = preferredTransports,
            )
            forward.attach(
                object : ControlSocketListener {
                    override fun onText(text: String) = transport.handleSocketText(text)
                    override fun onBinary(bytes: ByteArray) = transport.handleSocketBinary()
                    override fun onClosed(code: Int?, reason: String?) = transport.handleSocketClosed(code, reason)
                    override fun onFailure(error: Throwable) = transport.handleSocketFailure(error)
                },
            )
            val deferred = CompletableDeferred<Unit>()
            synchronized(transport.lock) { transport.ready = deferred }
            try {
                transport.sendHello(accessToken, clientVersion)
                withTimeout(timeoutMs) { deferred.await() }
            } catch (error: TimeoutCancellationException) {
                transport.abort()
                throw RemoteTransportException("CONNECTION_FAILED", "Relay control handshake timed out.")
            } catch (error: RemoteTransportException) {
                transport.abort()
                throw error
            } catch (error: Throwable) {
                transport.abort()
                throw error
            }
            return transport
        }

        private fun mismatchCode(error: ProtocolError): String {
            val code = error.code
            return if (code == null || code == "INVALID_MESSAGE") "PROTOCOL_MISMATCH" else code
        }

        private fun stringList(p: JsonObject, key: String): List<String>? {
            val array = p[key] as? JsonArray ?: return null
            return array.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        }
    }
}

/**
 * 在 [RelayTransport] 实例存在之前接住 socket 回调：先缓存，`attach` 之后原序补发。
 *
 * 这个窗口很短（`newWebSocket` → `connect` 返回），但 `hello.ack` 可能就落在里面。
 */
private class ForwardingListener : ControlSocketListener {
    private val early = mutableListOf<() -> Unit>()
    private var target: ControlSocketListener? = null

    fun attach(listener: ControlSocketListener) {
        synchronized(this) {
            target = listener
            for (action in early) action()
            early.clear()
        }
    }

    private fun dispatch(action: (ControlSocketListener) -> Unit) {
        synchronized(this) {
            val listener = target
            if (listener == null) {
                early.add { action(target!!) }
                return
            }
            action(listener)
        }
    }

    override fun onText(text: String) = dispatch { it.onText(text) }
    override fun onBinary(bytes: ByteArray) = dispatch { it.onBinary(bytes) }
    override fun onClosed(code: Int?, reason: String?) = dispatch { it.onClosed(code, reason) }
    override fun onFailure(error: Throwable) = dispatch { it.onFailure(error) }
}

/** [RelayTransport] 内部发起异步建链（数据 socket attach）用的作用域。 */
private object RelayScope {
    fun launch(block: suspend CoroutineScope.() -> Unit) {
        CoroutineScope(SupervisorJob() + RelayTransport.attachDispatcher).launch(block = block)
    }
}
