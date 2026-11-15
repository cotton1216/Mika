package com.xxt.zl.ai.ruyiremote.session

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话列表元数据的本地快照。
 *
 * 只缓存「列表」这一层（标题 / 时间 / 路径 / 是否在线），用途是冷启动或断线时先给
 * 一个骨架屏，避免「我的电脑 → 会话列表」先空白再加载。会话**正文（消息历史）绝不
 * 落盘**：那部分敏感、量大、且权威源在 Host，靠 follow 实时拉取即可，缓存它只会
 * 引入一致性问题和泄露面。
 *
 * 这里存的是非敏感元数据，不需要 Keystore 加密（账号凭据走的是 [com.xxt.zl.ai.ruyiremote.account.SecretStore]）。
 * namespace 按「中继 + 主机」隔离。只按中继地址是不够的：同一个中继下切换电脑时，
 * 上一台电脑的会话标题会先被读出来当骨架屏，直到 Host 回真实列表才被冲掉。
 */

/**
 * 快照隔离键：SHA-256 前 24 个十六进制字符。
 *
 * [hostId] 为空时与旧版逐字一致（按中继地址摘要），已落盘的旧快照仍能读到；
 * 传入主机 id 后用带长度前缀的拼接，避免 `a`+`bc` 与 `ab`+`c` 撞车。
 * 账号维度暂不参与：调用方目前拿不到可信的账号标识，不放假保证。
 */
fun sessionSnapshotNamespace(serverUrl: String, hostId: String? = null): String {
    val input = if (hostId.isNullOrEmpty()) {
        serverUrl
    } else {
        "$serverUrl\u0000host:${hostId.length}:$hostId"
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)
}

interface SessionSnapshotStore {
    suspend fun read(namespace: String): List<SessionSummary>?
    suspend fun write(namespace: String, sessions: List<SessionSummary>)
}

/** 单测用：纯内存，进程退出即失。控制器可能在多个协程里并发读写，用并发容器。 */
class InMemorySessionSnapshotStore : SessionSnapshotStore {
    private val cache = ConcurrentHashMap<String, List<SessionSummary>>()

    override suspend fun read(namespace: String): List<SessionSummary>? = cache[namespace]

    override suspend fun write(namespace: String, sessions: List<SessionSummary>) {
        cache[namespace] = sessions.toList()
    }
}

/** 真机：SharedPreferences 存 JSON。非敏感元数据，不走 Keystore 加密。 */
class SharedPrefsSessionSnapshotStore(context: Context) : SessionSnapshotStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("dsh_remote_session_snapshot", Context.MODE_PRIVATE)

    override suspend fun read(namespace: String): List<SessionSummary>? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(namespace, null) ?: return@withContext null
        runCatching { decodeSessionSnapshot(raw) }.getOrNull()
    }

    override suspend fun write(namespace: String, sessions: List<SessionSummary>) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(namespace, encodeSessionSnapshot(sessions)).apply()
        }
    }
}

// ---- SessionSummary ↔ JSON 序列化 ----
// internal 以便单测直接覆盖 round-trip；真机走 SharedPrefsSessionSnapshotStore。

internal fun encodeSessionSnapshot(sessions: List<SessionSummary>): String {
    val array = JsonArray(sessions.map { it.toJson() })
    return array.toString()
}

internal fun decodeSessionSnapshot(raw: String): List<SessionSummary> {
    val array = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonObject)?.toSummary() }
}

private fun SessionSummary.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    title?.let { put("title", it) }
    put("updatedAt", JsonPrimitive(updatedAt))
    put("running", running)
    cwd?.let { put("cwd", it) }
    origin?.let { put("origin", it) }
    put("blank", blank)
    put("agentAvailable", agentAvailable)
}

private fun JsonObject.toSummary(): SessionSummary? {
    val id = str("id") ?: return null
    return SessionSummary(
        id = id,
        title = str("title"),
        updatedAt = long("updatedAt") ?: 0,
        running = bool("running") == true,
        cwd = str("cwd"),
        origin = str("origin"),
        blank = bool("blank") == true,
        agentAvailable = bool("agentAvailable") == true,
    )
}
