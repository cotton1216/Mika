package com.xxt.zl.ai.ruyiremote.channel

import com.xxt.zl.ai.ruyiremote.crypto.NoiseIkSession
import com.xxt.zl.ai.ruyiremote.crypto.NoiseRole
import com.xxt.zl.ai.ruyiremote.crypto.NoiseSessionError
import com.xxt.zl.ai.ruyiremote.crypto.createNoisePrologue
import com.xxt.zl.ai.ruyiremote.protocol.ProtocolError
import com.xxt.zl.ai.ruyiremote.protocol.SecureMessageCodec
import com.xxt.zl.ai.ruyiremote.transport.RemoteConnectionInfo
import com.xxt.zl.ai.ruyiremote.transport.RemoteTransportClosed
import com.xxt.zl.ai.ruyiremote.transport.RemoteTransportException
import com.xxt.zl.ai.ruyiremote.transport.RemoteTransportMode
import com.xxt.zl.ai.ruyiremote.transport.SecureHandshakeTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** 安全通道结束的原因。 */
class SecureChannelClosed(
    val code: String,
    val message: String,
    val retryable: Boolean = true,
) {
    override fun toString(): String = "SecureChannelClosed($code): $message"
}

/** 建链/发送阶段的异常。[code] 同 [SecureChannelClosed]。 */
class SecureChannelException(
    val code: String,
    message: String,
    val retryable: Boolean = true,
) : Exception(message) {
    override fun toString(): String = "SecureChannelException($code): $message"
}

/**
 * 到一台 Host 的、带认证、保序、防重放的报文管道。
 *
 * * **握手**：客户端是 initiator，两侧 payload 都为空，10 秒硬超时；Host 的静态密钥
 *   必须等于已 pin 的 [hostIdentityKey]。
 * * **发送**：Remote 报文（≤ 4 MiB）→ DSHF 分片（> 48 KiB 才切）→ 每片一条 Noise
 *   消息，严格按 nonce 递增顺序发出。
 * * **接收**：Relay 的 `counter` 必须等于下一个 Noise 接收 nonce；任何重复、缺口、
 *   乱序、伪造、分片错误都会关闭通道（fail closed）。没有 rekey（上游路线图上的项）。
 *
 * 回调（`onMessage` / `onClosed`）在调用线程上**同步**触发；需要发消息时请
 * `launch` 到自己的 scope，不要在这里直接调挂起函数。
 */
class SecureChannel private constructor(
    private val transport: SecureHandshakeTransport,
    private val noise: NoiseIkSession,
) : SecureMessagePipe {

    private val lock = Any()
    private val sendMutex = Mutex()
    private val outgoing = SecureMessageCodec()
    private val incoming = SecureMessageCodec()

    private var handshakeDone: CompletableDeferred<Unit>? = null
    private var onMessage: ((ByteArray) -> Unit)? = null
    private var onClosed: ((SecureChannelClosed) -> Unit)? = null
    private val backlog = mutableListOf<ByteArray>()
    private var closedReason: SecureChannelClosed? = null
    private var closed = false

    val mode: RemoteTransportMode get() = transport.mode

    val info: RemoteConnectionInfo get() = transport.info

    val isClosed: Boolean get() = synchronized(lock) { closed }

    val closedCause: SecureChannelClosed? get() = synchronized(lock) { closedReason }

    // -----------------------------------------------------------------------
    // 握手

    private suspend fun handshake(timeoutMs: Long) {
        val done = CompletableDeferred<Unit>()
        synchronized(lock) { handshakeDone = done }
        transport.listen(
            onData = ::onData,
            onHandshake = ::onHandshakeMessage,
            onClosed = ::onTransportClosed,
        )
        try {
            transport.sendHandshake(1, noise.writeHandshake())
            withTimeout(timeoutMs) { done.await() }
        } catch (timeout: TimeoutCancellationException) {
            fail(SecureChannelClosed("SECURE_CHANNEL_FAILED", "Noise IK handshake timed out."))
            throw SecureChannelException("SECURE_CHANNEL_FAILED", "Noise IK handshake timed out.")
        } catch (error: RemoteTransportException) {
            fail(SecureChannelClosed(error.code, error.message.orEmpty(), error.retryable))
            throw SecureChannelException(error.code, error.message.orEmpty(), error.retryable)
        } catch (error: NoiseSessionError) {
            fail(SecureChannelClosed(error.code, error.message.orEmpty(), retryable = false))
            throw SecureChannelException(error.code, error.message.orEmpty(), retryable = false)
        }
    }

    private fun onHandshakeMessage(step: Int, data: ByteArray) {
        synchronized(lock) {
            val done = handshakeDone
            if (done == null || done.isCompleted || closed) return
            try {
                if (step != 2) {
                    throw NoiseSessionError(
                        "NOISE_HANDSHAKE_ORDER",
                        "Noise IK responder sent an out-of-order handshake message.",
                    )
                }
                noise.readHandshake(data)
                if (!noise.complete) {
                    throw NoiseSessionError("SECURE_CHANNEL_FAILED", "Noise IK handshake did not complete.")
                }
                done.complete(Unit)
            } catch (error: NoiseSessionError) {
                val code = if (error.code == "PEER_IDENTITY_MISMATCH") error.code else "SECURE_CHANNEL_FAILED"
                val retryable = code != "PEER_IDENTITY_MISMATCH"
                fail(SecureChannelClosed(code, error.message.orEmpty(), retryable))
                done.completeExceptionally(SecureChannelException(code, error.message.orEmpty(), retryable))
            }
        }
    }

    // -----------------------------------------------------------------------
    // 接收

    private fun onData(ciphertext: ByteArray, counter: Long?) {
        synchronized(lock) {
            if (closed) return
            try {
                if (!noise.complete) {
                    throw ProtocolError("Ciphertext arrived before the Noise handshake completed.")
                }
                if (counter != null && counter != noise.receivingCounter) {
                    throw ProtocolError("Relay counter is duplicated or out of order.")
                }
                val message = incoming.decode(noise.decrypt(ciphertext)) ?: return
                val handler = onMessage
                if (handler == null) backlog.add(message) else handler(message)
            } catch (error: Throwable) {
                fail(SecureChannelClosed("SECURE_CHANNEL_FAILED", "Secure channel input rejected: $error"))
            }
        }
    }

    private fun onTransportClosed(reason: RemoteTransportClosed) {
        synchronized(lock) {
            val done = handshakeDone
            if (done != null && !done.isCompleted) {
                done.completeExceptionally(
                    SecureChannelException(reason.code, reason.message, reason.retryable),
                )
            }
            fail(SecureChannelClosed(reason.code, reason.message, reason.retryable))
        }
    }

    /**
     * 注册接收方。此前已解出的报文会**按序立即补发**。
     * [onClosed] 只触发一次；通道已关时注册也会立刻收到。
     */
    override fun listen(onMessage: (message: ByteArray) -> Unit, onClosed: (SecureChannelClosed) -> Unit) {
        synchronized(lock) {
            this.onMessage = onMessage
            this.onClosed = onClosed
            val pending = backlog.toList()
            backlog.clear()
            for (message in pending) onMessage(message)
            closedReason?.let(onClosed)
        }
    }

    // -----------------------------------------------------------------------
    // 发送

    /**
     * 发一条 Remote 报文。调用之间串行化；任何失败都会关闭通道。
     *
     * [canSend] 可以在第一条分片被编码/加密之前撤掉一条排队中的报文；
     * 一旦开始发送就不再检查。
     */
    override suspend fun send(message: ByteArray, canSend: (() -> Boolean)?) {
        sendMutex.withLock {
            if (canSend?.invoke() == false) return
            sendNow(message)
        }
    }

    private suspend fun sendNow(message: ByteArray) {
        val current = synchronized(lock) { if (closed) closedReason else null }
        if (current != null) {
            throw SecureChannelException(current.code, "The secure channel is closed.")
        }
        try {
            for (fragment in outgoing.encode(message)) {
                val counter = noise.sendingCounter
                transport.send(noise.encrypt(fragment), counter)
            }
        } catch (error: ProtocolError) {
            // 报文超长：通道本身没坏，只拒这一条。
            if (error.code == "FRAME_TOO_LARGE") {
                throw SecureChannelException("FRAME_TOO_LARGE", error.message.orEmpty(), retryable = false)
            }
            throw error
        } catch (error: Throwable) {
            fail(SecureChannelClosed("SECURE_CHANNEL_FAILED", "Secure send failed: $error"))
            throw error
        }
    }

    // -----------------------------------------------------------------------
    // 收尾

    private fun fail(reason: SecureChannelClosed) {
        // 自己取锁：调用点有的已持锁（synchronized 可重入），有的没有（握手超时）。
        synchronized(lock) {
            if (closed) return
            closed = true
            closedReason = reason
            noise.destroy()
            incoming.reset()
            outgoing.reset()
            scope.launch { runCatching { transport.close() } }
            onClosed?.invoke(reason)
        }
    }

    /**
     * 关闭通道。[reason] 用来保留一个本地的协议失败原因，
     * 而不是把它报成「客户端主动断开」。
     */
    override suspend fun close(reason: SecureChannelClosed?) {
        synchronized(lock) {
            if (closed) return
            fail(
                reason ?: SecureChannelClosed(
                    "CLIENT_CLOSED",
                    "The client closed the secure channel.",
                    retryable = false,
                ),
            )
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 建立安全通道并跑完 Noise IK 握手。 */
        suspend fun establish(
            transport: SecureHandshakeTransport,
            clientDeviceId: String,
            clientPrivateKey: String,
            clientPublicKey: String,
            hostDeviceId: String,
            hostIdentityKey: String,
            timeoutMs: Long = 10_000,
        ): SecureChannel {
            val info = transport.info
            if (info.localDeviceId != clientDeviceId || info.remoteDeviceId != hostDeviceId) {
                runCatching { transport.close() }
                throw SecureChannelException(
                    "SECURE_CHANNEL_FAILED",
                    "The relay connection is bound to an unexpected device.",
                    retryable = false,
                )
            }
            val noise = try {
                NoiseIkSession(
                    role = NoiseRole.initiator,
                    localPrivateKey = clientPrivateKey,
                    localPublicKey = clientPublicKey,
                    remotePublicKey = hostIdentityKey,
                    prologue = createNoisePrologue(info.connectionId, hostDeviceId, clientDeviceId),
                )
            } catch (error: NoiseSessionError) {
                runCatching { transport.close() }
                throw SecureChannelException(error.code, error.message.orEmpty(), retryable = false)
            }
            val channel = SecureChannel(transport, noise)
            channel.handshake(timeoutMs)
            return channel
        }
    }
}
