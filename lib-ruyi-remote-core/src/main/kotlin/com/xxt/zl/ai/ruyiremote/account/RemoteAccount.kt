package com.xxt.zl.ai.ruyiremote.account

import android.util.Log
import com.xxt.zl.ai.ruyiremote.channel.RemoteRpcClient
import com.xxt.zl.ai.ruyiremote.channel.SecureChannel
import com.xxt.zl.ai.ruyiremote.channel.SecureChannelClosed
import com.xxt.zl.ai.ruyiremote.channel.SecureMessagePipe
import com.xxt.zl.ai.ruyiremote.net.DEFAULT_SERVER_URL
import com.xxt.zl.ai.ruyiremote.net.DeviceDescriptor
import com.xxt.zl.ai.ruyiremote.net.DeviceTokenPair
import com.xxt.zl.ai.ruyiremote.net.RegistrationCode
import com.xxt.zl.ai.ruyiremote.net.RemoteApiException
import com.xxt.zl.ai.ruyiremote.net.RemoteServerApi
import com.xxt.zl.ai.ruyiremote.net.normalizeRegistrationCode
import com.xxt.zl.ai.ruyiremote.transport.RelayTransport
import com.xxt.zl.ai.ruyiremote.transport.SecureHandshakeTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** 配对码换来的设备拿不到账号名，用这个局部标签代替（UI 再翻译成人话）。 */
const val CODE_ACCOUNT_LABEL = "code"

private const val TAG = "RuyiRemote"

/** Host 侧能力条目上限（协议限制）。 */
private const val MAX_CAPABILITY_ENTRIES = 128

/**
 * 一次连接/账号操作的失败。
 *
 * [code] 用的是协议里的 code，不是给用户看的文案 —— UI 负责翻译。这样
 * 「令牌过期该重试」和「身份键变了必须停」能靠 code 分流，而不是靠字符串匹配。
 */
class RemoteConnectException(
    val code: String,
    message: String,
    val retryable: Boolean = false,
) : Exception(message) {

    override fun toString(): String = "RemoteConnectException($code): $message"
}

/** 已登录的账号。 */
data class RemoteUser(
    val account: String,
    val deviceId: String,
    val serverUrl: String,
    val provider: String? = null,
)

/** 本机对这个 Host 身份的信任状态。 */
enum class HostTrust {
    /** 已 pin 的身份键与服务端现在报的一致。 */
    pinned,

    /** 还没连过，首次连接时 pin。 */
    unpinned,

    /** 服务端报的键与 pin 的不同 —— 拒绝连接，直到用户主动重新信任。 */
    keyChanged,

    /** 拿不到描述。 */
    unknown,
}

/** 本账号可连的一台 Host。 */
data class RemoteHost(
    val id: String,
    val name: String,
    val platform: String,
    val online: Boolean,
    val trust: HostTrust,
    val harnessVersion: String? = null,
    val pluginVersion: String? = null,
    val lastSeenAt: Long? = null,
    val identityKey: String? = null,
    val pinnedIdentityKey: String? = null,
    val membershipId: String? = null,
) {
    /** `A1B2 C3D4 E5F6`，与 Host 端显示的那串比对即可确认身份。 */
    val fingerprint: String?
        get() = identityKey?.let { formatFingerprint(identityFingerprint(it)) }
}

/** 一条打通到 Host 的连接：Noise 通道已建立、能力已探明。 */
class HostConnection(
    val transportMode: String,
    val harnessVersion: String?,
    val capabilities: List<String>,
    val workspaceTypes: JsonElement?,
    private val channel: SecureMessagePipe,
    val rpc: RemoteRpcClient,
) {
    /** 后续会话层（`session.*` 等 RPC）都走这个入口。 */
    suspend fun call(method: String, params: JsonElement? = null, timeoutMs: Long? = null): JsonElement? =
        rpc.call(method, params, timeoutMs)

    suspend fun close() {
        runCatching { rpc.close() }
        runCatching { channel.close() }
    }

    /**
     * 系统层报告网络丢失：立即以 `CONNECTION_FAILED` 判死当前通道，触发上层自动重连。
     *
     * 静默断线（无 FIN/RST）时底层 socket 不会报错，只能靠中继心跳 watchdog
     * （默认 25s×3=75s）判死。系统 `ConnectivityManager` 的 `onLost` 能亚秒感知网络
     * 丢失，这里把它转成一次「被动断开」，让 `rpc.closed` 立刻完成、会话层无缝续传。
     */
    suspend fun onNetworkLost() {
        runCatching {
            channel.close(SecureChannelClosed("CONNECTION_FAILED", "Network lost.", retryable = true))
        }
    }
}

private class Credentials(
    val deviceId: String,
    val account: String,
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    val refreshTokenExpiresAt: Long,
    val provider: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1)
        put("deviceId", deviceId)
        put("account", account)
        put("accessToken", accessToken)
        put("accessTokenExpiresAt", JsonPrimitive(accessTokenExpiresAt))
        put("refreshToken", refreshToken)
        put("refreshTokenExpiresAt", JsonPrimitive(refreshTokenExpiresAt))
        provider?.let { put("provider", it) }
    }

    companion object {
        fun of(deviceId: String, account: String, tokens: DeviceTokenPair, provider: String?) = Credentials(
            deviceId = deviceId,
            account = account,
            accessToken = tokens.accessToken,
            accessTokenExpiresAt = tokens.accessTokenExpiresAt,
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
            provider = provider,
        )

        fun parse(raw: String?): Credentials? {
            if (raw.isNullOrEmpty()) return null
            return try {
                val map = Json.parseToJsonElement(raw) as JsonObject
                Credentials(
                    deviceId = map.str("deviceId") ?: return null,
                    account = map.str("account") ?: return null,
                    accessToken = map.str("accessToken") ?: return null,
                    accessTokenExpiresAt = map.num("accessTokenExpiresAt") ?: return null,
                    refreshToken = map.str("refreshToken") ?: return null,
                    refreshTokenExpiresAt = map.num("refreshTokenExpiresAt") ?: return null,
                    provider = map.str("provider"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

private data class HostPin(val identityKey: String, val name: String, val pinnedAt: Long)

/**
 * 一个账号在一台中继上的全部状态：身份、令牌、Host 目录与 pin。
 *
 * 与 Dart `RemoteAccount` 同构，但**去掉了会话层**（DshClient / mux / Typert
 * Session）—— 那部分是另一个数量级的工程，这里做到「Noise 通道打通 + 能力探明」
 * 为止，[HostConnection] 把 RPC 出口留出来给后续会话层。
 *
 * 所有机密（设备私钥、令牌、pin）都分区存在 [SecretStore]：分区键带 server origin
 * 的哈希，换一台中继不会串号。
 */
class RemoteAccount(
    private val secrets: SecretStore,
    serverUrl: String = DEFAULT_SERVER_URL,
    private val deviceName: String = "如意远程",
    /**
     * 设备平台标签。**只是给人看的**：中继只校验格式（`^[a-z0-9][a-z0-9._-]*$`）并原样存进
     * 设备描述符，不做任何分支；桌面端用 `formatPlatform()` 显示，只把 darwin/win32/linux/android
     * 映射成好看的名字，其余原样吐出来。所以这里用 `android` 而不是自造前缀，
     * 对端「已连接的客户端」里就会显示成「Android」。
     */
    private val platform: String = "android",
    private val clientVersion: String = "0.1.0",
    private val api: RemoteServerApi = RemoteServerApi(serverUrl),
    /** 注入点：单测替换成假 socket，生产用 OkHttp。 */
    private val connectTransport: (suspend (url: String, deviceId: String, token: String, hostId: String) -> SecureHandshakeTransport)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val serverUrl: String = api.serverUrl.toString().trimEnd('/')
    val socketUrl: String = api.socketUrl

    private val scope: String = MessageDigest.getInstance("SHA-256")
        .digest(serverUrl.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(24)

    private val identityKey = "dsh_remote.v1.$scope.identity"
    private val credentialsKey = "dsh_remote.v1.$scope.credentials"
    private val pinsKey = "dsh_remote.v1.$scope.pins"

    private var identity: DeviceIdentity? = null
    private var credentials: Credentials? = null
    private var pins: MutableMap<String, HostPin>? = null
    private var restored = false

    /** 刷新令牌会轮换，重放旧令牌会吊销整个令牌族 —— 刷新必须单飞。 */
    private val refreshLock = Mutex()

    private var parsedCredentials: Credentials? = null

    private suspend fun restore() {
        if (restored) return
        restored = true
        val rawIdentity = secrets.read(identityKey)
        identity = DeviceIdentity.parse(rawIdentity)
        val rawCredentials = secrets.read(credentialsKey)
        parsedCredentials = Credentials.parse(rawCredentials)
        credentials = parsedCredentials
            // 令牌与身份必须同源：身份轮换过（重装 / 重新登录）时旧令牌一律作废。
            ?.takeIf { it.deviceId == identity?.deviceId }
        // 恢复失败以前是全静默的：界面直接退回配对页，用户和排查的人都不知道
        // 是「没存过」还是「存了但解不开」。只记长度与 id 前缀，不落任何密文。
        if (identity == null) {
            Log.w(TAG, "恢复失败：设备身份缺失或不可解密（密文 ${rawIdentity?.length ?: 0} 字符）")
        }
        if (parsedCredentials == null) {
            Log.w(TAG, "恢复失败：凭据缺失或不可解密（密文 ${rawCredentials?.length ?: 0} 字符）")
        } else if (credentials == null) {
            Log.w(
                TAG,
                "恢复失败：凭据与当前身份不同源（凭据 ${parsedCredentials?.deviceId?.take(8)}… " +
                    "≠ 身份 ${identity?.deviceId?.take(8)}…）",
            )
        }
    }

    /**
     * 强制下一轮 [restore] 重新读一次 SecretStore，而不是用内存缓存。
     *
     * 常驻会话的账号实例只在冷启动 restore 过一次（`restored=true` 后就短路）；而登录/
     * 退出联动走的是**另一个** [RemoteAccount] 实例、写的是同一份 Keystore。不主动失效，
     * 这个实例就永远拿内存里的旧值 —— 登录了进不去、退出了下不来。所以换设备令牌后
     * 必须显式 reload 一次，让 [currentUser] 重新看到磁盘上的真实状态。
     *
     * pin 不在这里重读：它独立于凭据、按 Host 键值存，登录/退出都不该动它（与
     * [logout] 里「pin 保留」的语义一致）。
     */
    suspend fun reload() {
        restored = false
        identity = null
        credentials = null
        parsedCredentials = null
        restore()
    }

    /**
     * 为什么「有存档却进不去」。
     *
     * 返回 null 表示本来就没存过（新装），那种情况用户看到配对码是正常的；
     * 返回非 null 表示存过但已作废，登录页要明确说出来，否则用户只会觉得
     * 「怎么又要我配对」。
     */
    suspend fun restoreIssue(): String? {
        restore()
        val raw = parsedCredentials ?: return null
        return when {
            identity == null -> "本机保存的设备身份已丢失，需要重新配对或登录。"
            raw.deviceId != identity?.deviceId -> "本机保存的登录与当前设备身份对不上，需要重新配对或登录。"
            raw.refreshTokenExpiresAt <= clock() + 30_000 -> "上次的登录已过期，需要重新配对或登录。"
            else -> null
        }
    }

    /** 当前登录用户；凭据缺失或刷新令牌已过期都算没登录。 */
    suspend fun currentUser(): RemoteUser? {
        restore()
        val current = credentials ?: return null
        if (current.refreshTokenExpiresAt <= clock() + 30_000) return null
        return RemoteUser(current.account, current.deviceId, serverUrl, current.provider)
    }

    /** 邮箱密码登录（自建中继的主要方式）。 */
    suspend fun loginWithPassword(email: String, password: String): RemoteUser {
        val login = api.loginWithPassword(email, password)
        return registerWithAccountToken(login.token, login.account, "password")
    }

    /**
     * 凭一次性配对码绑定本机。
     *
     * 账号由码本身携带，所以这条路上不经过任何账号密码；标签优先取中继返回的
     * 显示名，老中继没有那个接口就退回 [CODE_ACCOUNT_LABEL]。
     */
    suspend fun loginWithCode(code: String): RemoteUser {
        val normalized = normalizeRegistrationCode(code)
        require(normalized.isNotEmpty()) { "An authorization code is required." }
        restore()
        var current = ensureIdentity()
        var tokens = try {
            api.redeemRegistrationCode(normalized, descriptor(current))
        } catch (e: RemoteApiException) {
            // 被吊销 / 身份冲突时换一把新身份重试：兑换码本身就是一次显式重新授权，
            // 且这两条路径上码并未被消费，重试安全。
            if (e.code != "DEVICE_REVOKED" && e.code != "PEER_IDENTITY_MISMATCH") throw connectError(e)
            current = ensureIdentity(rotate = true)
            api.redeemRegistrationCode(normalized, descriptor(current))
        }
        val label = api.accountDisplayName(tokens.accessToken) ?: CODE_ACCOUNT_LABEL
        saveCredentials(Credentials.of(current.deviceId, label, tokens, CODE_ACCOUNT_LABEL))
        return RemoteUser(label, current.deviceId, serverUrl, CODE_ACCOUNT_LABEL)
    }

    /**
     * 用**账号会话令牌**登录（例如浏览器 OIDC 换来的票）。
     *
     * 会话令牌只用于把本机注册成 client 设备，之后一律用设备令牌 —— 账号侧
     * 短时效令牌过期不会踢掉已有连接。
     */
    suspend fun loginWithAccountToken(accountToken: String, account: String): RemoteUser =
        registerWithAccountToken(accountToken, account, null)

    /**
     * 用**如意 AI App 那次登录拿到的 AI-Server `access_token`** 登录：先在中继换成账号会话
     * （[RemoteServerApi.loginWithAiServerToken]），再把本机注册成 client 设备。
     *
     * 这是「一个登录」的落点：如意 AI 登录已经花掉了那枚一次性授权码，远程控制不再自己发起
     * 授权，只复用同一张账号票。中继读的是同一个 AI-Server profile，所以设备挂在同一个
     * 账号下，桌面上看到的就是同一个人。
     */
    suspend fun loginWithAiServerToken(accessToken: String): RemoteUser {
        val session = api.loginWithAiServerToken(accessToken)
        return registerWithAccountToken(session.token, session.account, null)
    }

    private suspend fun registerWithAccountToken(
        accountToken: String,
        account: String,
        provider: String?,
    ): RemoteUser {
        restore()
        var current = ensureIdentity()
        var tokens = try {
            api.registerDevice(descriptor(current), accountToken)
        } catch (e: RemoteApiException) {
            if (e.code != "DEVICE_REVOKED" && e.code != "PEER_IDENTITY_MISMATCH") throw connectError(e)
            current = ensureIdentity(rotate = true)
            api.registerDevice(descriptor(current), accountToken)
        }
        saveCredentials(Credentials.of(current.deviceId, account, tokens, provider))
        return RemoteUser(account, current.deviceId, serverUrl, provider)
    }

    private fun descriptor(identity: DeviceIdentity) = DeviceDescriptor(
        deviceId = identity.deviceId,
        name = identity.name,
        role = "client",
        platform = identity.platform,
        identityKey = identity.publicKey,
        clientVersion = clientVersion,
    )

    private suspend fun ensureIdentity(rotate: Boolean = false): DeviceIdentity {
        val existing = identity
        if (existing != null && !rotate) return existing
        val created = DeviceIdentity.create(deviceName, platform)
        secrets.write(identityKey, created.toJson().toString())
        identity = created
        // 身份换新 = 服务端上的旧设备记录作废，旧令牌没有意义。
        credentials = null
        secrets.delete(credentialsKey)
        return created
    }

    private suspend fun saveCredentials(next: Credentials) {
        secrets.write(credentialsKey, next.toJson().toString())
        credentials = next
    }

    /**
     * 一个可用的设备访问令牌；离过期不足 60 秒就先刷新。
     *
     * 并发调用共享同一次刷新 —— 刷新令牌每用一次就轮换，两个并发刷新里必然有
     * 一个拿着旧令牌，会把整个令牌族吊销掉。
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String {
        restore()
        val current = credentials ?: throw RemoteConnectException(
            "ACCOUNT_AUTH_REQUIRED",
            "Sign in to the remote Server first.",
        )
        if (!forceRefresh && current.accessTokenExpiresAt > clock() + 60_000) return current.accessToken
        return refreshLock.withLock {
            // 拿到锁再看一眼：可能别人刚刷完。
            val latest = credentials
            if (!forceRefresh && latest != null && latest.accessTokenExpiresAt > clock() + 60_000) {
                return@withLock latest.accessToken
            }
            rotate(latest ?: current)
        }
    }

    private suspend fun rotate(current: Credentials): String {
        if (current.refreshTokenExpiresAt <= clock()) {
            dropCredentials()
            throw RemoteConnectException(
                "ACCOUNT_AUTH_REQUIRED",
                "The device sign-in expired; sign in again.",
            )
        }
        val tokens = try {
            api.refresh(current.deviceId, current.refreshToken)
        } catch (e: RemoteApiException) {
            if (e.retryable || e.code == "CONNECTION_FAILED" || e.code == "RATE_LIMITED") {
                throw RemoteConnectException(e.code, e.message ?: "refresh failed", retryable = true)
            }
            dropCredentials()
            throw RemoteConnectException(
                "ACCOUNT_AUTH_REQUIRED",
                "The Server refused the device token (${e.code}); sign in again.",
            )
        }
        saveCredentials(Credentials.of(current.deviceId, current.account, tokens, current.provider))
        return tokens.accessToken
    }

    private suspend fun dropCredentials() {
        credentials = null
        secrets.delete(credentialsKey)
    }

    /** 签一枚配对码，让另一台设备（通常是桌面 Host）加入本账号。 */
    suspend fun createRegistrationCode(): RegistrationCode = withToken { api.createRegistrationCode(it) }

    /**
     * 本账号的 Host，带在线状态、版本与 pin 结论。
     *
     * 每台 Host 的描述都要单独拉（列表接口不下发 identityKey），并发拉，
     * 单个失败只让那一台变成 [HostTrust.unknown]，不拖垮整张表。
     */
    suspend fun listHosts(): List<RemoteHost> {
        val listings = try {
            withToken { api.listHosts(it) }
        } catch (e: RemoteApiException) {
            throw connectError(e)
        }
        val pins = loadPins()
        return listings.map { listing ->
            val peer = try {
                val token = accessToken()
                try {
                    api.device(token, listing.deviceId)
                } catch (e: RemoteApiException) {
                    if (!e.isAuthFailure) throw e
                    api.device(accessToken(forceRefresh = true), listing.deviceId)
                }
            } catch (_: Exception) {
                null
            }
            val key = peer?.takeIf { it.role == "host" && isIdentityKey(it.identityKey) }?.identityKey
            val pin = pins[listing.deviceId]
            val trust = when {
                key == null -> HostTrust.unknown
                pin == null -> HostTrust.unpinned
                constantTimeEquals(pin.identityKey.toByteArray(), key.toByteArray()) -> HostTrust.pinned
                else -> HostTrust.keyChanged
            }
            RemoteHost(
                id = listing.deviceId,
                name = listing.name,
                platform = listing.platform,
                online = peer?.online ?: listing.online,
                trust = trust,
                harnessVersion = listing.harnessVersion ?: peer?.harnessVersion,
                pluginVersion = listing.clientVersion ?: peer?.clientVersion,
                lastSeenAt = peer?.lastSeenAt ?: listing.lastSeenAt,
                identityKey = key,
                pinnedIdentityKey = pin?.identityKey,
                membershipId = listing.membershipId,
            )
        }
    }

    /** 忘掉一台 Host 的 pin：Host 合法重装后才该用它。 */
    suspend fun forgetHost(hostId: String) {
        val current = loadPins()
        if (current.remove(hostId) != null) savePins(current)
    }

    private suspend fun loadPins(): MutableMap<String, HostPin> {
        pins?.let { return it }
        val raw = secrets.read(pinsKey)
        val result = mutableMapOf<String, HostPin>()
        if (!raw.isNullOrEmpty()) {
            runCatching {
                val hosts = (Json.parseToJsonElement(raw) as JsonObject)["hosts"] as? JsonObject
                hosts?.forEach { (id, value) ->
                    val map = value as? JsonObject ?: return@forEach
                    val key = map.str("identityKey") ?: return@forEach
                    result[id] = HostPin(key, map.str("name") ?: "", map.num("pinnedAt") ?: 0)
                }
            }
        }
        pins = result
        return result
    }

    private suspend fun savePins(next: Map<String, HostPin>) {
        val json = buildJsonObject {
            put("v", 1)
            put("hosts", buildJsonObject {
                next.forEach { (id, pin) ->
                    put(id, buildJsonObject {
                        put("identityKey", pin.identityKey)
                        put("name", pin.name)
                        put("pinnedAt", JsonPrimitive(pin.pinnedAt))
                    })
                }
            })
        }
        secrets.write(pinsKey, json.toString())
        pins = next.toMutableMap()
    }

    /**
     * 连到一台 Host：pin 校验 → 中继建链 → Noise 握手 → 探明能力。
     *
     * 顺序是刻意固定的 —— **pin 校验在开 socket 之前**，身份对不上就一个字节都
     * 不发；Noise 也要等数据面确定后才开始，因为中继链路和目标 Host 绑定。
     */
    suspend fun connect(host: RemoteHost, timeoutMs: Long = 30_000): HostConnection {
        restore()
        val currentIdentity = identity ?: throw RemoteConnectException(
            "ACCOUNT_AUTH_REQUIRED", "Sign in to the remote Server first.",
        )
        if (credentials == null) {
            throw RemoteConnectException("ACCOUNT_AUTH_REQUIRED", "Sign in to the remote Server first.")
        }
        var token = accessToken()

        val peer = try {
            api.device(token, host.id)
        } catch (e: RemoteApiException) {
            if (!e.isAuthFailure) throw connectError(e)
            token = accessToken(forceRefresh = true)
            api.device(token, host.id)
        }
        if (peer.role != "host" || peer.deviceId != host.id || !isIdentityKey(peer.identityKey)) {
            throw RemoteConnectException("INVALID_MESSAGE", "The Server returned an invalid Host descriptor.")
        }
        if (peer.online == false) {
            throw RemoteConnectException("HOST_OFFLINE", "The Host is offline.", retryable = true)
        }
        val hostKey = checkPin(host, peer)

        val transport = try {
            buildTransport(token, host.id)
        } catch (e: RemoteConnectException) {
            if (e.code != "AUTH_INVALID" && e.code != "TOKEN_EXPIRED") throw e
            token = accessToken(forceRefresh = true)
            buildTransport(token, host.id)
        }

        val channel = SecureChannel.establish(
            transport = transport,
            clientDeviceId = currentIdentity.deviceId,
            clientPrivateKey = currentIdentity.privateKey,
            clientPublicKey = currentIdentity.publicKey,
            hostDeviceId = host.id,
            hostIdentityKey = hostKey,
        )
        val rpc = RemoteRpcClient(channel)
        val mode = transport.mode.name

        val description = try {
            rpc.call("harness.transport.describe", JsonObject(emptyMap()), timeoutMs = 10_000)
        } catch (e: Exception) {
            runCatching { rpc.close() }
            runCatching { channel.close() }
            throw RemoteConnectException(
                "CONNECTION_FAILED",
                "The Host did not answer the capability probe: ${e.message}",
                retryable = true,
            )
        }?.jsonObject ?: run {
            runCatching { rpc.close() }
            runCatching { channel.close() }
            throw RemoteConnectException("INVALID_MESSAGE", "The Host returned an empty capability probe.")
        }

        val raw = description["capabilities"] as? List<*>
        val capabilities = raw?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (capabilities == null || raw.size > MAX_CAPABILITY_ENTRIES ||
            capabilities.any { it.isEmpty() } || capabilities.toSet().size != capabilities.size
        ) {
            runCatching { rpc.close() }
            runCatching { channel.close() }
            throw RemoteConnectException("INVALID_MESSAGE", "The Host returned invalid transport capabilities.")
        }
        val v3 = "harness.remote.v3" in capabilities
        val v1 = "harness.remote.v1" in capabilities
        if (v3 && v1) {
            runCatching { rpc.close() }
            runCatching { channel.close() }
            throw RemoteConnectException(
                "INVALID_MESSAGE", "The Host advertised conflicting Harness Session formats.",
            )
        }
        if (!v3) {
            runCatching { rpc.close() }
            runCatching { channel.close() }
            throw RemoteConnectException(
                "HARNESS_VERSION_INCOMPATIBLE",
                if (v1) {
                    "The Host runs a pre-0.1.5 Ruyi (Session format v1); this client needs Ruyi 0.2.x (Session V3)."
                } else {
                    "The Host exposes no Typert Remote carrier (harness.remote.v3); update its ds-harness-remote plugin."
                },
            )
        }
        return HostConnection(
            transportMode = mode,
            harnessVersion = peer.harnessVersion,
            capabilities = capabilities,
            workspaceTypes = description["workspaceTypes"],
            channel = channel,
            rpc = rpc,
        )
    }

    private suspend fun buildTransport(token: String, hostId: String): SecureHandshakeTransport {
        val identity = this.identity ?: throw RemoteConnectException(
            "ACCOUNT_AUTH_REQUIRED", "Sign in to the remote Server first.",
        )
        val injected = connectTransport
        if (injected != null) return injected(socketUrl, identity.deviceId, token, hostId)
        return RelayTransport.connect(
            url = socketUrl,
            deviceId = identity.deviceId,
            accessToken = token,
            hostDeviceId = hostId,
            clientVersion = clientVersion,
        )
    }

    /**
     * pin 校验。首次连接直接 pin（有确认回调时先问），之后**必须一字不差**。
     *
     * 这里的比对是纵深防御：真正的确认点在服务端下发的 `identityKey` 与本地 pin
     * 之比。IK 握手本身也会失败（用错公钥算 es/ss，第 1 条消息就解不开），但那时
     * 报的是 `NOISE_DECRYPT_FAILED`，语义远不如 `PEER_IDENTITY_MISMATCH` 清楚。
     */
    private suspend fun checkPin(host: RemoteHost, peer: com.xxt.zl.ai.ruyiremote.net.AuthorizedPeer): String {
        val current = loadPins()
        val pinned = current[host.id]
        if (pinned != null) {
            if (!constantTimeEquals(
                    pinned.identityKey.toByteArray(Charsets.UTF_8),
                    peer.identityKey.toByteArray(Charsets.UTF_8),
                )
            ) {
                throw RemoteConnectException(
                    "PEER_IDENTITY_MISMATCH",
                    "The identity key of Host \"${host.name}\" changed (pinned " +
                        "${formatFingerprint(identityFingerprint(pinned.identityKey))}, " +
                        "Server reports ${formatFingerprint(identityFingerprint(peer.identityKey))}). " +
                        "Refusing to connect.",
                )
            }
            return pinned.identityKey
        }
        current[host.id] = HostPin(peer.identityKey, peer.name, clock())
        savePins(current)
        return peer.identityKey
    }

    /** 退出登录：吊销设备（尽力而为）并删本地令牌；pin 保留。 */
    suspend fun logout(revokeDevice: Boolean = true) {
        restore()
        val current = credentials
        if (revokeDevice && current != null) {
            runCatching { api.revokeSelf(accessToken()) }
        }
        credentials = null
        secrets.delete(credentialsKey)
        if (revokeDevice) {
            identity = null
            secrets.delete(identityKey)
        }
    }

    private suspend fun <T> withToken(action: suspend (String) -> T): T {
        val token = accessToken()
        return try {
            action(token)
        } catch (e: RemoteApiException) {
            if (!e.isAuthFailure) throw e
            action(accessToken(forceRefresh = true))
        }
    }
}

/** 把 REST 失败翻译成连接失败，保留 code 与可重试性。 */
private fun connectError(e: RemoteApiException): RemoteConnectException = RemoteConnectException(
    e.code,
    e.message ?: "Server request failed.",
    retryable = e.retryable || e.code == "CONNECTION_FAILED" || e.code == "HOST_OFFLINE",
)

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.num(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
