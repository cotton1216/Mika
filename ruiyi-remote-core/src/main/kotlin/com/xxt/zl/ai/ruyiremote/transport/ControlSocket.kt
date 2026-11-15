package com.xxt.zl.ai.ruyiremote.transport

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 一条文本 WebSocket。抽出来是为了让 [RelayTransport] 能在单测里换成假 socket——
 * 与 Dart 侧 `ControlSocketConnector` 可注入是同一个目的。
 */
interface ControlSocket {
    /** 发送一条文本帧。已关闭时抛 [IOException]。 */
    fun send(text: String)

    /** 正常关闭；不做等待，关闭结果走 [ControlSocketListener.onClosed]。 */
    fun close(code: Int, reason: String)

    /** 立刻断开（不等待关闭握手），用于取消建立中的连接。 */
    fun cancel()
}

/** socket 回调。实现方负责切到串行上下文，回调本身不保证线程。 */
interface ControlSocketListener {
    fun onText(text: String)

    /** 对端发了二进制帧。协议只允许文本，交给上层判成协议错误。 */
    fun onBinary(bytes: ByteArray)

    fun onClosed(code: Int?, reason: String?)

    fun onFailure(error: Throwable)
}

/** 建链失败，带 HTTP 状态码（OkHttp 的握手失败会给出响应）。 */
class ControlSocketException(
    message: String,
    val httpStatusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

fun interface ControlSocketConnector {
    suspend fun connect(url: String, timeoutMs: Long, listener: ControlSocketListener): ControlSocket
}

/**
 * OkHttp 实现。
 *
 * 超时语义与 Dart 的 `_defaultConnector` 一致：超时**不会**取消建立过程本身，
 * 所以这里在 `withTimeout` 取消时显式 `cancel()` 掉那条 WebSocket，避免留下
 * 一条没人收的活连接（Dart 侧是用 `expired` 标志补 close，效果相同）。
 */
class OkHttpControlSocketConnector(private val client: OkHttpClient) : ControlSocketConnector {

    override suspend fun connect(
        url: String,
        timeoutMs: Long,
        listener: ControlSocketListener,
    ): ControlSocket = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(url).build()
            // OkHttp 的 onOpen / onFailure 只会来一个，但 onFailure 可能在 onOpen
            // 之后又来（链路中途断开），用标志区分「建链失败」与「链路失效」。
            var opened = false

            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened = true
                        if (cont.isActive) cont.resume(OkHttpControlSocket(webSocket))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        listener.onText(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        listener.onBinary(bytes.toByteArray())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // 对端发了 close：回一个 close 完成握手，onClosed 随后到达。
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        listener.onClosed(code, reason)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (opened) {
                            listener.onFailure(t)
                            return
                        }
                        // 建链失败：HTTP 状态码是判定 AUTH_INVALID 的唯一依据。
                        val error = ControlSocketException(
                            message = t.message ?: t.toString(),
                            httpStatusCode = response?.code,
                            cause = t,
                        )
                        if (cont.isActive) cont.resumeWithException(error) else listener.onFailure(error)
                    }
                },
            )
            cont.invokeOnCancellation { socket.cancel() }
        }
    }
}

private class OkHttpControlSocket(private val socket: WebSocket) : ControlSocket {
    override fun send(text: String) {
        if (!socket.send(text)) throw IOException("The WebSocket is closed.")
    }

    override fun close(code: Int, reason: String) {
        socket.close(code, reason)
    }

    override fun cancel() {
        socket.cancel()
    }
}
