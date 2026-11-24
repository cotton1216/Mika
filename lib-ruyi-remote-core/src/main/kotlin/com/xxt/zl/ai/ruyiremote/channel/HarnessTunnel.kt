package com.xxt.zl.ai.ruyiremote.channel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID

/** Host 通过隧道拒绝一次调用；原样保留 `code/details`，便于界面给出诊断码。 */
class HarnessException(
    val code: String,
    message: String,
    val details: JsonElement? = null,
) : Exception(message)

/**
 * `harness.remote.*` 隧道：一元调用与流。
 *
 * 与 Dart `RemoteTunnelCarrier` 的关键点一致：流的订阅先于 `stream.open` 登记，
 * 因此 Host 在 open 应答之前抢跑发出的帧不会丢；`stream.closed=failed` 变成
 * [HarnessException]，`peer-disconnected` 变成 [RemoteRpcException]。取消订阅
 * 会向 Host 发 `stream.close`，释放 Host 侧消费者。
 */
class HarnessTunnel(
    private val rpc: RemoteRpcClient,
    private val capabilities: List<String> = emptyList(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : HarnessTransport {
    private val transferCapable = capabilities.contains("harness.remote.stream.transfer.v1")
    private val active = HashMap<String, ActiveStream>()

    private class ActiveStream {
        val incoming = Channel<Incoming>(Channel.UNLIMITED)
        var job: Job? = null
    }

    private sealed interface Incoming {
        data class Frame(val data: JsonObject) : Incoming
        data class Closed(val reason: String, val failure: JsonObject?) : Incoming
    }

    private val unsubscribe = rpc.addEventListener(::onEvent)

    /** `harness.remote.call`，返回 `{ok:true,value}` 中的 value。 */
    override suspend fun call(endpoint: String, args: JsonObject, timeoutMs: Long?): JsonElement? {
        val response = rpc.call(
            "harness.remote.call",
            buildJsonObject {
                put("endpoint", endpoint)
                put("payload", buildJsonObject { put("args", args) })
            },
            timeoutMs,
        )
        return when (val value = response as? JsonObject) {
            null -> throw HarnessException("INVALID_MESSAGE", "$endpoint: 隧道返回了非对象结果")
            else -> {
                if (value["ok"] == JsonPrimitive(true)) return value["value"]
                val error = value["error"] as? JsonObject
                if (value["ok"] == JsonPrimitive(false) && error != null) {
                    throw HarnessException(
                        error.stringValue("code") ?: "internal",
                        error.stringValue("message") ?: "$endpoint 失败",
                        error["details"],
                    )
                }
                throw HarnessException("INVALID_MESSAGE", "$endpoint: 隧道返回格式不合法")
            }
        }
    }

    /**
     * `harness.remote.stream.open` + `harness.remote.frame`。
     * 冷流：每次 collect 打开一条独立的 Host 流。
     */
    override fun stream(endpoint: String, args: JsonObject): Flow<JsonElement> = callbackFlow {
        val streamId = UUID.randomUUID().toString()
        val stream = ActiveStream()
        val out = Channel<JsonElement>(Channel.UNLIMITED)
        synchronized(active) { active[streamId] = stream }
        stream.job = scope.launch {
            try {
                for (item in stream.incoming) {
                    when (item) {
                        is Incoming.Frame -> {
                            val value = if (item.data.containsKey("transfer")) readFrame(streamId, item.data) else item.data["value"]
                            if (value != null) out.send(value)
                        }
                        is Incoming.Closed -> {
                            if (item.reason == "failed") {
                                val failure = item.failure
                                out.close(
                                    HarnessException(
                                        failure?.stringValue("code") ?: "internal",
                                        failure?.stringValue("message") ?: "远程流失败",
                                        failure?.get("details"),
                                    ),
                                )
                            } else if (item.reason == "peer-disconnected") {
                                out.close(RemoteRpcException("TRANSPORT_CLOSED", "Host 断开了远程流", isLocal = true))
                            } else {
                                out.close()
                            }
                            return@launch
                        }
                    }
                }
                out.close()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                out.close(error)
            }
        }
        // 把 actor 的输出转给 Flow；out 结束即结束订阅。
        val pump = launch {
            for (value in out) send(value)
            close()
        }
        // 整条通道断掉时 Host 来不及发 `stream.closed`：必须由本侧结束订阅，
        // 否则界面会一直停在「实时同步中」，而所有写操作都只报 TRANSPORT_CLOSED。
        val guard = launch {
            val reason = rpc.closed.await()
            val code = if (reason.code == "CLIENT_CLOSED") "CLIENT_CLOSED" else "TRANSPORT_CLOSED"
            out.close(RemoteRpcException(code, "远程通道已断开：${reason.message}", isLocal = true))
        }
        try {
            rpc.call(
                "harness.remote.stream.open",
                buildJsonObject {
                    put("streamId", streamId)
                    put("endpoint", endpoint)
                    put("payload", buildJsonObject { put("args", args) })
                    if (transferCapable) put("transferFrames", true)
                },
                streamOpenTimeoutMs,
            )
        } catch (error: Throwable) {
            closeRemote(streamId)
            close(error)
        }
        awaitClose {
            pump.cancel()
            guard.cancel()
            stream.job?.cancel()
            stream.incoming.close()
            synchronized(active) { active.remove(streamId) }
            closeRemote(streamId)
        }
    }

    private fun onEvent(event: RemoteEvent) {
        val data = event.data as? JsonObject ?: return
        val streamId = data.stringValue("streamId") ?: return
        val stream = synchronized(active) { active[streamId] } ?: return
        when (event.event) {
            "harness.remote.frame" -> {
                if (data["hasValue"] == JsonPrimitive(true)) stream.incoming.trySend(Incoming.Frame(data))
            }
            "harness.remote.stream.closed" -> {
                stream.incoming.trySend(
                    Incoming.Closed(data.stringValue("reason") ?: "completed", data["failure"] as? JsonObject),
                )
            }
        }
    }

    /** 大帧以 `harness.remote.transfer.*` 分块传输；顺序、长度、base64 都做校验。 */
    private suspend fun readFrame(streamId: String, data: JsonObject): JsonElement? {
        val descriptor = data["transfer"] as? JsonObject
            ?: throw HarnessException("INVALID_MESSAGE", "流帧缺少传输描述")
        if (!transferCapable || data.containsKey("value")) {
            throw HarnessException("INVALID_MESSAGE", "Host 使用了未协商的流传输")
        }
        val transferId = descriptor.stringValue("transferId")
            ?: throw HarnessException("INVALID_MESSAGE", "传输描述缺少 transferId")
        runCatching { UUID.fromString(transferId) }.getOrElse {
            throw HarnessException("INVALID_MESSAGE", "transferId 不是 UUID")
        }
        val totalBytes = descriptor.intValue("totalBytes")?.toInt()
            ?: throw HarnessException("INVALID_MESSAGE", "传输描述缺少 totalBytes")
        val totalChunks = descriptor.intValue("totalChunks")?.toInt()
            ?: throw HarnessException("INVALID_MESSAGE", "传输描述缺少 totalChunks")
        if (descriptor.stringValue("kind") != "chunked" ||
            totalBytes <= 0 || totalBytes > MAX_STREAM_TRANSFER_BYTES ||
            totalChunks != (totalBytes + TRANSFER_CHUNK_BYTES - 1) / TRANSFER_CHUNK_BYTES
        ) {
            throw HarnessException("INVALID_MESSAGE", "流传输描述不合法")
        }
        try {
            val bytes = ByteArray(totalBytes)
            var offset = 0
            for (index in 0 until totalChunks) {
                val chunk = rpc.call(
                    "harness.remote.transfer.read",
                    buildJsonObject { put("transferId", transferId); put("index", index) },
                    20_000,
                ) as? JsonObject ?: throw HarnessException("INVALID_MESSAGE", "传输分块不是对象")
                val encoded = chunk.stringValue("data")
                    ?: throw HarnessException("INVALID_MESSAGE", "传输分块缺少 data")
                if (chunk.stringValue("transferId") != transferId || chunk.intValue("index") != index.toLong()) {
                    throw HarnessException("INVALID_MESSAGE", "传输分块乱序")
                }
                val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse {
                    throw HarnessException("INVALID_MESSAGE", "传输分块 base64 不合法")
                }
                val expected = minOf(TRANSFER_CHUNK_BYTES, totalBytes - index * TRANSFER_CHUNK_BYTES)
                if (decoded.size != expected || Base64.getEncoder().encodeToString(decoded) != encoded) {
                    throw HarnessException("INVALID_MESSAGE", "传输分块长度或编码不规范")
                }
                decoded.copyInto(bytes, offset)
                offset += decoded.size
            }
            val frame = runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrElse {
                throw HarnessException("INVALID_MESSAGE", "传输帧不是 JSON 对象")
            }
            if (frame.stringValue("streamId") != streamId ||
                frame["hasValue"] != JsonPrimitive(true) ||
                frame.containsKey("transfer")
            ) {
                throw HarnessException("INVALID_MESSAGE", "传输帧内容不合法")
            }
            return frame["value"]
        } finally {
            runCatching {
                rpc.call(
                    "harness.remote.transfer.close",
                    buildJsonObject { put("transferId", transferId) },
                    5_000,
                )
            }
        }
    }

    private fun closeRemote(streamId: String) {
        if (rpc.isClosed) return
        scope.launch {
            runCatching {
                rpc.call(
                    "harness.remote.stream.close",
                    buildJsonObject { put("streamId", streamId) },
                    streamOpenTimeoutMs,
                )
            }
        }
    }
}

/** Host 上限：一次传输 288 MiB，分块解码后 512 KiB。 */
const val MAX_STREAM_TRANSFER_BYTES = 288 * 1024 * 1024
const val TRANSFER_CHUNK_BYTES = 512 * 1024

private const val streamOpenTimeoutMs = 15_000L

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intValue(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
