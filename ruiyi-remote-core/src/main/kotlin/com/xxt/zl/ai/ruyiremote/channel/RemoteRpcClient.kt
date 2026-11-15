package com.xxt.zl.ai.ruyiremote.channel

import com.xxt.zl.ai.ruyiremote.protocol.ProtocolError
import com.xxt.zl.ai.ruyiremote.protocol.RemoteMessage
import com.xxt.zl.ai.ruyiremote.protocol.RpcErrorPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.CopyOnWriteArrayList

/** 每条连接允许的在飞 RPC 上限（协议限制）。 */
const val MAX_PENDING_RPC = 128

/**
 * 一次 Remote RPC 失败。
 *
 * Host 侧的拒绝带 Host 的 `rpc.error` code（`METHOD_NOT_ALLOWED`、`RPC_TIMEOUT`、
 * `RESPONSE_TOO_LARGE`、`RATE_LIMITED`、`TERMINAL_DISABLED`…）；本地失败用
 * `RPC_TIMEOUT`（[isLocal]）或 `TRANSPORT_CLOSED` / `CLIENT_CLOSED`。
 */
class RemoteRpcException(
    val code: String,
    message: String,
    val details: JsonElement? = null,
    val retryable: Boolean = false,
    /** 是本侧抛的（超时、通道丢失），不是 Host 回的。 */
    val isLocal: Boolean = false,
) : Exception(message) {
    val isTransportClosed: Boolean get() = code == "TRANSPORT_CLOSED" || code == "CLIENT_CLOSED"

    override fun toString(): String = "RemoteRpcException($code${if (isLocal) ", local" else ""}): ${message}"
}

/** `event` 消息的 payload：`{event, data, seq?, sessionId?}`。 */
class RemoteEvent(
    val event: String,
    val data: JsonElement?,
    val seq: Long? = null,
)

/**
 * 按 message id 做请求/响应配对、事件分发、超时。
 *
 * 每个请求恰好一个结局；迟到的响应直接丢弃。事件监听器按到达顺序**同步**回调，
 * 所以要先用 [addEventListener] 订阅，再发会触发事件的 RPC。
 */
class RemoteRpcClient(
    private val channel: SecureMessagePipe,
    private val defaultTimeoutMs: Long = 35_000,
    /**
     * [call] 里「不等写入完成」的那条协程用的调度器。
     *
     * 生产用默认调度器；单测换成 `Dispatchers.Unconfined` 才能在 `call` 返回前
     * 把出站帧落实，避免用 sleep 等竞态。
     */
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private class Pending(
        val method: String,
        val deferred: CompletableDeferred<JsonElement?>,
        val timer: Job,
    )

    private val lock = Any()
    private val pending = HashMap<String, Pending>()
    private val done = CompletableDeferred<SecureChannelClosed>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val listeners = CopyOnWriteArrayList<(RemoteEvent) -> Unit>()

    @Volatile
    private var lastInboundNanos = System.nanoTime()

    init {
        channel.listen(::onMessage, ::onClosed)
    }

    /** 通道没了的时候完成一次。 */
    val closed: CompletableDeferred<SecureChannelClosed> get() = done

    val isClosed: Boolean get() = done.isCompleted

    /** Host 上次发来任何东西（响应、事件、流帧）距今多久。 */
    val sinceInboundMs: Long get() = (System.nanoTime() - lastInboundNanos) / 1_000_000L

    /** 订阅事件，返回取消订阅的函数。 */
    fun addEventListener(listener: (RemoteEvent) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /**
     * 发一次 RPC 并等结果。
     *
     * 抛出 [RemoteRpcException]：Host 拒绝时为 Host 的 code，
     * 本侧超时为 `RPC_TIMEOUT`（[RemoteRpcException.isLocal] = true）。
     */
    suspend fun call(method: String, params: JsonElement? = null, timeoutMs: Long? = null): JsonElement? {
        synchronized(lock) {
            if (done.isCompleted) {
                throw RemoteRpcException(
                    "TRANSPORT_CLOSED",
                    "RPC $method: the remote channel is closed.",
                    isLocal = true,
                )
            }
            if (pending.size >= MAX_PENDING_RPC) {
                throw RemoteRpcException(
                    "RATE_LIMITED",
                    "Too many pending remote RPCs.",
                    retryable = true,
                    isLocal = true,
                )
            }
        }
        val request = RemoteMessage.rpcRequest(method, params)
        val deferred = CompletableDeferred<JsonElement?>()
        val deadline = timeoutMs ?: defaultTimeoutMs
        val timer = scope.launch {
            delay(deadline)
            reject(
                request.id,
                RemoteRpcException(
                    "RPC_TIMEOUT",
                    "RPC $method timed out after $deadline ms.",
                    retryable = true,
                    isLocal = true,
                ),
            )
        }
        synchronized(lock) { pending[request.id] = Pending(method, deferred, timer) }

        val encoded = try {
            request.encode()
        } catch (error: Throwable) {
            // 还没把 Future 交给调用方：只退掉这一次预留。
            take(request.id)
            throw error
        }
        // 不等写入完成：超时或关通道必须能把这条 RPC 结掉，哪怕 send 一直不返回。
        // 更早的一条 send 可能把这帧压在队列里超过期限，所以 canSend 要在**编码第一条
        // Noise 分片之前**问一次，绝不能在一组分片中间问。
        scope.launch {
            try {
                channel.send(encoded) { synchronized(lock) { pending.containsKey(request.id) } }
            } catch (error: SecureChannelException) {
                reject(
                    request.id,
                    if (error.code == "FRAME_TOO_LARGE") {
                        RemoteRpcException("FRAME_TOO_LARGE", error.message.orEmpty(), isLocal = true)
                    } else {
                        RemoteRpcException(
                            "TRANSPORT_CLOSED",
                            "RPC $method send failed: $error",
                            retryable = true,
                            isLocal = true,
                        )
                    },
                )
            } catch (error: Throwable) {
                reject(
                    request.id,
                    RemoteRpcException(
                        "TRANSPORT_CLOSED",
                        "RPC $method send failed: $error",
                        retryable = true,
                        isLocal = true,
                    ),
                )
            }
        }
        return deferred.await()
    }

    private fun onMessage(bytes: ByteArray) {
        lastInboundNanos = System.nanoTime()
        val message = try {
            RemoteMessage.decode(bytes)
        } catch (error: ProtocolError) {
            // 明文不合法就 fail closed，不留半条连接。
            val reason = SecureChannelClosed("SECURE_CHANNEL_FAILED", "Invalid Remote message: $error")
            scope.launch { runCatching { channel.close(reason) } }
            onClosed(reason)
            return
        }
        when (message.type) {
            "rpc.response" -> {
                val payload = message.payload as? JsonObject ?: return
                val requestId = payload.string("requestId") ?: return
                take(requestId)?.deferred?.complete(payload["result"])
            }

            "rpc.error" -> {
                val error = try {
                    RpcErrorPayload.parse(message.payload)
                } catch (parse: ProtocolError) {
                    return
                }
                take(error.requestId)?.deferred?.completeExceptionally(
                    RemoteRpcException(
                        error.code,
                        error.message,
                        details = error.details as? JsonElement,
                        retryable = error.retryable ?: false,
                    ),
                )
            }

            "event" -> {
                val payload = message.payload as? JsonObject ?: return
                val name = payload.string("event") ?: return
                // 同步回调：监听器里可以直接调 close()（synchronized 可重入），
                // 但不该在这里阻塞——它跑在拆帧线程上。
                val event = RemoteEvent(name, payload["data"], payload.long("seq"))
                for (listener in listeners) listener(event)
            }

            // Host 发来的 rpc.request 不属于客户端契约。
            else -> Unit
        }
    }

    private fun take(requestId: String): Pending? = synchronized(lock) {
        val entry = pending.remove(requestId)
        entry?.timer?.cancel()
        entry
    }

    private fun reject(requestId: String, error: RemoteRpcException) {
        take(requestId)?.deferred?.completeExceptionally(error)
    }

    private fun onClosed(reason: SecureChannelClosed) {
        synchronized(lock) {
            if (done.isCompleted) return
            done.complete(reason)
            val code = if (reason.code == "CLIENT_CLOSED") "CLIENT_CLOSED" else "TRANSPORT_CLOSED"
            // 复制一份 key：reject 会改 map。
            for (id in pending.keys.toList()) {
                val entry = pending[id] ?: continue
                reject(
                    id,
                    RemoteRpcException(
                        code,
                        "RPC ${entry.method} ended: ${reason.message}",
                        retryable = reason.retryable,
                        isLocal = true,
                    ),
                )
            }
        }
    }

    /** 关掉通道与所有在飞 RPC。 */
    suspend fun close() {
        runCatching { channel.close() }
        onClosed(
            SecureChannelClosed(
                "CLIENT_CLOSED",
                "The client closed the remote connection.",
                retryable = false,
            ),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.intOrNull?.toLong() ?: primitive.contentOrNull?.toLongOrNull()
    }
}
