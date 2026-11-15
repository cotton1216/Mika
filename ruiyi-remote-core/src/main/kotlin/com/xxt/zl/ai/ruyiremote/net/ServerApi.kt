package com.xxt.zl.ai.ruyiremote.net

import com.xxt.zl.ai.ruyiremote.BuildConfig
import com.xxt.zl.ai.ruyiremote.account.isIdentityKey
import com.xxt.zl.ai.ruyiremote.util.intValue
import com.xxt.zl.ai.ruyiremote.util.stringValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 默认中继（本项目自建的那台）。
 *
 * 与 Dart 侧的 `defaultRemoteServer`（`https://dsh.r2049.cn`，别人的部署）不同 ——
 * 这里连的是我们自己的中继，IAM / 设备账号都在它上面。
 *
 * 构建期可换：`-PrelayDefault=http://10.0.2.2:8899`（模拟器访问宿主上的本地中继）。
 */
val DEFAULT_SERVER_URL: String = BuildConfig.RELAY_SERVER_URL

/** JS 安全整数上限：超过它的时间戳一律视为非法响应。 */
private const val MAX_SAFE_INTEGER = 9007199254740991L

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** REST 用的客户端：与 WebSocket 那个分开，这里要有限读超时。 */
internal val restHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * 服务端 REST 失败。
 *
 * [code] 来自 `{error:{code,…}}` 信封，或按 HTTP 状态映射（[statusCode]）。
 * 与 Dart `RemoteApiException` 同一套 code：`AUTH_INVALID` / `TOKEN_EXPIRED` /
 * `ACCOUNT_AUTH_REQUIRED` / `DEVICE_NOT_FOUND` / `DEVICE_REVOKED` /
 * `MEMBERSHIP_REQUIRED` / `PEER_IDENTITY_MISMATCH` / `RATE_LIMITED` /
 * `CONNECTION_FAILED` / `INVALID_MESSAGE`。
 */
class RemoteApiException(
    val code: String,
    message: String,
    val status: Int? = null,
    val retryable: Boolean = false,
) : Exception(message) {

    /** 换个新令牌重试就可能成功的失败。 */
    val isAuthFailure: Boolean
        get() = code == "AUTH_INVALID" || code == "TOKEN_EXPIRED" ||
            code == "AUTH_REQUIRED" || code == "ACCOUNT_AUTH_REQUIRED"

    override fun toString(): String =
        "RemoteApiException($code${status?.let { ", HTTP $it" } ?: ""}): ${message}"
}

/**
 * 把用户输入的配对码收紧成服务端的规范形状（`^[A-Z0-9]{4}-[A-Z0-9]{4}$`）。
 *
 * 为什么必须做：桌面端显示的是 `UXVG-Y3ET`，但用户手抄或复制粘贴时带进来的
 * 往往是全角连字符 `－`(U+FF0D)、em dash `—`(U+2014)、空格，或者干脆不写连字符。
 * 原样发给服务端只会得到一句 `AUTH_INVALID`，让人以为码错了或过期了——
 * 实测在模拟器上用 IME 输入就踩过：界面上看着像 `UXVG-Y3ET`，实际不是 ASCII 连字符。
 *
 * 长度不是 8 位字母数字时原样返回（只 trim/大写），不擅自猜测 —— 让服务端按
 * 自己的规则报错，比在客户端编一个「格式不对」更可信。
 */
fun normalizeRegistrationCode(raw: String): String {
    val compact = raw.filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }.uppercase()
    return if (compact.length == 8) "${compact.take(4)}-${compact.drop(4)}" else raw.trim().uppercase()
}

/**
 * 把用户输入的服务器地址规范成「裸 origin」。
 *
 * 规则与 Dart `normalizeServerUrl` 一致，逐条都有理由：
 * * 去掉末尾斜杠 —— 后面拼接路径都用 `resolve`，多余斜杠会产生 `//api`；
 * * 没写 scheme 就补 `https://` —— 用户习惯只输域名；
 * * 带 userInfo / query / fragment / 非根路径一律拒绝 —— 这些都意味着
 *   「这不是一台服务器，而是一个链接」，拼路径会得到意外结果；
 * * 强制 HTTPS，只有本机 / 内网地址允许 HTTP（自建调试用）。
 */
fun normalizeServerUrl(input: String): HttpUrl {
    var text = input.trim()
    while (text.endsWith("/")) text = text.dropLast(1)
    require(text.isNotEmpty()) { "serverUrl is empty" }
    if (!Regex("^[a-z][a-z\\d+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        text = "https://$text"
    }
    val url = text.toHttpUrlOrNull() ?: throw IllegalArgumentException("serverUrl is not a URL")
    require(url.username.isEmpty() && url.password.isEmpty()) { "serverUrl must be a bare origin" }
    require(url.query == null && url.fragment == null) { "serverUrl must be a bare origin" }
    require(url.encodedPath == "/") { "serverUrl must be a bare origin" }
    val scheme = url.scheme.lowercase()
    require(scheme == "https" || (scheme == "http" && isPrivateHost(url.host))) {
        "serverUrl must use HTTPS (HTTP only for local/private hosts)"
    }
    return url
}

private fun isPrivateHost(host: String): Boolean {
    if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]" || host == "10.0.2.2") {
        return true
    }
    val match = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(host)
        ?: return host.endsWith(".local") || host.endsWith(".test") || host.endsWith(".localhost")
    val a = match.groupValues[1].toInt()
    val b = match.groupValues[2].toInt()
    return a == 10 || a == 127 ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254) ||
        (a == 100 && b in 64..127)
}

/**
 * `wss://<host>/ws/v1/connect`。HTTPS → WSS，HTTP（内网调试）→ WS。
 *
 * 不能用 `HttpUrl.Builder.scheme("wss")` —— OkHttp 的 HttpUrl 只认 http/https，
 * 换个 scheme 直接抛 `unexpected scheme: wss`。所以这里拼字符串，端口只在
 * 非默认端口时才写出来。
 */
fun controlSocketUrl(server: HttpUrl): String {
    val scheme = if (server.scheme == "https") "wss" else "ws"
    val defaultPort = HttpUrl.defaultPort(server.scheme)
    val host = if (server.host.contains(':')) "[${server.host}]" else server.host
    val port = if (server.port == defaultPort) "" else ":${server.port}"
    return "$scheme://$host$port/ws/v1/connect"
}

/** 注册时上报的设备描述。客户端描述**不带** `harnessVersion`（那是 Host 的事）。 */
data class DeviceDescriptor(
    val deviceId: String,
    val name: String,
    val role: String,
    val platform: String,
    val identityKey: String,
    val clientVersion: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("deviceId", deviceId)
        put("name", name)
        put("role", role)
        put("platform", platform)
        put("identityKey", identityKey)
        put("clientVersion", clientVersion)
    }

    /** 与 Dart `validate()` 同样严格：不合格的描述在本地就拒，别送去服务端试。 */
    fun validate() {
        val uuid = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )
        val ulid = Regex("^[0-9A-HJKMNP-TV-Z]{26}$", RegexOption.IGNORE_CASE)
        require(uuid.matches(deviceId) || ulid.matches(deviceId)) { "deviceId must be a UUID or ULID" }
        val nameBytes = name.toByteArray(Charsets.UTF_8).size
        require(name.isNotBlank() && nameBytes <= 128) { "name must be 1..128 bytes of visible text" }
        require(role == "client" || role == "host") { "role must be client or host" }
        require(platform.length <= 64 && Regex("^[a-z0-9][a-z0-9._-]*$").matches(platform)) {
            "platform must match [a-z0-9][a-z0-9._-]* (<= 64)"
        }
        require(isIdentityKey(identityKey)) { "identityKey must be a canonical 32-byte base64url key" }
        val versionBytes = clientVersion.toByteArray(Charsets.UTF_8).size
        require(versionBytes in 1..64) { "clientVersion must be 1..64 bytes" }
    }
}

/**
 * 设备访问令牌 + 刷新令牌。
 *
 * 刷新令牌**每用一次就轮换**，重放旧令牌会吊销整个令牌族 —— 所以刷新必须单飞，
 * 见 `RemoteAccount.accessToken` 上的互斥锁。
 */
data class DeviceTokenPair(
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    val refreshTokenExpiresAt: Long,
) {
    companion object {
        private val ALLOWED = setOf(
            "accessToken", "accessTokenExpiresAt", "refreshToken", "refreshTokenExpiresAt",
        )

        fun parse(input: JsonElement?): DeviceTokenPair {
            val map = input as? JsonObject
                ?: throw invalid("The server returned invalid device credentials.")
            fun token(key: String): String? {
                val value = map.str(key) ?: return null
                val size = value.toByteArray(Charsets.UTF_8).size
                return if (size in 16..8192) value else null
            }
            fun expiry(key: String): Long? {
                val value = map.intValue(key) ?: return null
                return if (value in 1..MAX_SAFE_INTEGER) value else null
            }
            if (map.keys.any { it !in ALLOWED }) throw invalid("The server returned invalid device credentials.")
            return DeviceTokenPair(
                accessToken = token("accessToken") ?: throw invalid("The server returned invalid device credentials."),
                accessTokenExpiresAt = expiry("accessTokenExpiresAt")
                    ?: throw invalid("The server returned invalid device credentials."),
                refreshToken = token("refreshToken") ?: throw invalid("The server returned invalid device credentials."),
                refreshTokenExpiresAt = expiry("refreshTokenExpiresAt")
                    ?: throw invalid("The server returned invalid device credentials."),
            )
        }
    }
}

/** 账号会话换来的登录结果（`POST /api/v1/auth/login`）。 */
data class AccountLogin(val token: String, val account: String, val expiresAt: Long?)

/** 一次性配对码 `XXXX-XXXX`：让另一台设备凭码加入本账号。 */
data class RegistrationCode(val code: String, val expiresAt: Long) {
    companion object {
        fun parse(input: JsonElement?): RegistrationCode {
            val map = input as? JsonObject
                ?: throw invalid("The server returned an invalid registration code.")
            val code = map.str("code")
            val expiresAt = map.intValue("expiresAt")
            if (map.keys.any { it != "code" && it != "expiresAt" } ||
                code == null || !Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}$").matches(code) ||
                expiresAt == null || expiresAt !in 1..MAX_SAFE_INTEGER
            ) {
                throw invalid("The server returned an invalid registration code.")
            }
            return RegistrationCode(code, expiresAt)
        }
    }
}

/** `GET /api/v1/devices` 的一条：**不含** identityKey。 */
data class ServerHostListing(
    val deviceId: String,
    val name: String,
    val platform: String,
    val membershipId: String,
    val online: Boolean,
    val clientVersion: String? = null,
    val harnessVersion: String? = null,
    val lastSeenAt: Long? = null,
)

/** `GET /api/v1/devices/{id}`：带 identityKey 的被授权对端描述。 */
data class AuthorizedPeer(
    val deviceId: String,
    val name: String,
    val role: String,
    val platform: String,
    val identityKey: String,
    val membershipId: String,
    val online: Boolean? = null,
    val lastSeenAt: Long? = null,
    val clientVersion: String? = null,
    val harnessVersion: String? = null,
)

private fun invalid(message: String) = RemoteApiException("INVALID_MESSAGE", message)

/**
 * 一个 ds-harness-remote 服务端的 REST 客户端（`/api/v1/…`）。
 *
 * 设计约束，全部来自 Dart 实现并被测试钉住：
 * * 令牌按次传入，**不落日志、不进 URL**（`Authorization: Bearer`）；
 * * 不跟重定向 —— 3xx 视为失败，否则令牌可能被带到别的源；
 * * 跨源请求直接拒 —— 防「伪造成服务器返回的路径」变成任意请求转发器；
 * * 响应一律严格解析：字段多余、类型不对、超出安全整数，都算 `INVALID_MESSAGE`。
 */
class RemoteServerApi(
    serverUrl: String,
    private val client: OkHttpClient = restHttpClient,
    private val timeoutMs: Long = 10_000,
) {
    val serverUrl: HttpUrl = normalizeServerUrl(serverUrl)

    val socketUrl: String get() = controlSocketUrl(serverUrl)

    suspend fun health() {
        val body = request("GET", "/health") as? JsonObject
        if (body.str("status") != "ok") {
            throw RemoteApiException("CONNECTION_FAILED", "The server health check failed.")
        }
    }

    /** 邮箱密码登录：返回账号会话令牌（只用来注册设备，随后丢弃）。 */
    suspend fun loginWithPassword(email: String, password: String): AccountLogin {
        val body = request(
            "POST", "/api/v1/auth/login",
            body = buildJsonObject {
                put("email", email.trim())
                put("password", password)
            },
        ) as? JsonObject
        val token = body.str("token")
        val account = body.str("account")
        if (token == null || token.length < 16 || account.isNullOrEmpty()) {
            throw invalid("The server returned an invalid account login.")
        }
        return AccountLogin(token, account, body?.intValue("expiresAt"))
    }

    /**
     * 用**别处已经登录拿到的 AI-Server `access_token`** 换中继账号会话
     * （`POST /api/v1/auth/iam`，请求体 `{token}`）。
     *
     * 存在的理由只有一个：AI-Server 的授权码一次性、60 秒过期，同一个码换不出两份票。
     * 如愿 App 的登录已经把码换成了 access_token，远程控制若再走一次授权就是第二个登录页。
     * 中继这个入口把同一个 access_token 交给 `iamFromToken` 读 profile，与
     * `/api/v1/auth/aiserver/exchange` 调的是同一个 helper、同一个账号主键，
     * 所以「一个登录、两个用途」不会变成两个账号。
     *
     * 名字里的 iam 只是中继内部的模块名（它读的是 AI-Server profile），不是另一套登录方式。
     * 令牌按次传入、只在本请求里用一次：不落日志、不进 URL。
     */
    suspend fun loginWithAiServerToken(accessToken: String): AccountLogin {
        if (accessToken.length < 20) throw invalid("The access token is too short.")
        val body = request(
            "POST", "/api/v1/auth/iam",
            body = buildJsonObject { put("token", accessToken) },
        ) as? JsonObject
        val token = body.str("token")
        val account = body.str("account")
        if (token == null || token.length < 16 || account.isNullOrEmpty()) {
            throw invalid("The server returned an invalid account login.")
        }
        return AccountLogin(token, account, body?.intValue("expiresAt"))
    }

    /** `GET /api/v1/auth/me`：账号会话令牌 → 账号名。 */
    suspend fun accountMe(accountToken: String): String {
        val body = request("GET", "/api/v1/auth/me", bearer = accountToken) as? JsonObject
        val account = body.str("account")
        if (account.isNullOrEmpty()) throw invalid("The server returned an invalid account profile.")
        return account
    }

    /** `POST /api/v1/devices/register`：把本机注册成 client 设备，换设备令牌对。 */
    suspend fun registerDevice(device: DeviceDescriptor, accountToken: String): DeviceTokenPair {
        device.validate()
        return DeviceTokenPair.parse(
            request(
                "POST", "/api/v1/devices/register",
                body = buildJsonObject {
                    put("v", 1)
                    put("device", device.toJson())
                },
                bearer = accountToken,
            ),
        )
    }

    /** `POST /api/v1/devices/register-with-code`：凭一次性配对码绑定本机。 */
    suspend fun redeemRegistrationCode(code: String, device: DeviceDescriptor): DeviceTokenPair {
        device.validate()
        return DeviceTokenPair.parse(
            request(
                "POST", "/api/v1/devices/register-with-code",
                body = buildJsonObject {
                    put("v", 1)
                    put("code", normalizeRegistrationCode(code))
                    put("device", device.toJson())
                },
            ),
        )
    }

    /** `POST /api/v1/account/registration-codes`：签一枚 `XXXX-XXXX` 给别的设备用。 */
    suspend fun createRegistrationCode(bearer: String): RegistrationCode =
        RegistrationCode.parse(request("POST", "/api/v1/account/registration-codes", bearer = bearer))

    /**
     * `GET /api/v1/account/profile`：本账号显示名。
     *
     * 配对码换来的设备拿不到账号会话，只有一个局部标签；这里按设备令牌读一次
     * 真实显示名。旧中继没有该接口 → 返回 null，调用方继续用局部标签。
     */
    suspend fun accountDisplayName(bearer: String): String? {
        val body = try {
            request("GET", "/api/v1/account/profile", bearer = bearer)
        } catch (_: RemoteApiException) {
            return null
        }
        val name = (body as? JsonObject)?.str("displayName")?.trim()
        return name?.takeIf { it.isNotEmpty() }
    }

    /** `POST /api/v1/auth/refresh`：轮换刷新令牌（旧令牌随即失效）。 */
    suspend fun refresh(deviceId: String, refreshToken: String): DeviceTokenPair =
        DeviceTokenPair.parse(
            request(
                "POST", "/api/v1/auth/refresh",
                body = buildJsonObject {
                    put("deviceId", deviceId)
                    put("refreshToken", refreshToken)
                },
            ),
        )

    /**
     * 本账号可连的 Host。服务端分页（默认 50、最大 100），游标是 opaque；
     * 老版本一次返回全量且没有游标，循环在首页就自然结束，两种都能用。
     */
    suspend fun listHosts(accessToken: String): List<ServerHostListing> {
        val pageSize = 100
        val maxPages = 100
        val hosts = mutableListOf<ServerHostListing>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        for (page in 0 until maxPages) {
            val query = if (cursor == null) {
                "?limit=$pageSize"
            } else {
                "?limit=$pageSize&cursor=${urlEncodeQuery(cursor)}"
            }
            val body = request("GET", "/api/v1/devices$query", bearer = accessToken) as? JsonObject
            val items = body?.get("items") as? List<*>
                ?: throw invalid("The server returned an invalid device list.")
            for (raw in items) {
                val item = raw as? JsonObject ?: continue
                if (item.str("role") != "host") continue
                val deviceId = item.str("deviceId") ?: continue
                val name = item.str("name") ?: continue
                val platform = item.str("platform") ?: continue
                val membershipId = item.str("membershipId") ?: continue
                if (membershipId.isEmpty()) continue
                val lastSeen = item.intValue("lastSeenAt")
                hosts += ServerHostListing(
                    deviceId = deviceId,
                    name = name,
                    platform = platform,
                    membershipId = membershipId,
                    online = item.booleanValue("online") == true,
                    clientVersion = item.str("clientVersion"),
                    harnessVersion = item.str("harnessVersion"),
                    lastSeenAt = lastSeen?.takeIf { it > 0 },
                )
            }
            val next = body.str("nextCursor")
            if (next.isNullOrEmpty()) break
            if (!seenCursors.add(next)) throw invalid("The server repeated a device-list cursor.")
            cursor = next
        }
        return hosts
    }

    /**
     * `GET /api/v1/devices/{id}`：带 identityKey 的对端描述。
     *
     * 存在性单独查：新服务端把 `online` 拆到 `/presence`，老服务端内联。
     * **缺失不等于离线** —— 拿不到 presence 时报错，绝不把「认证失败」或
     * 「响应畸形」降级成「Host 离线」。
     */
    suspend fun device(accessToken: String, deviceId: String): AuthorizedPeer {
        val path = "/api/v1/devices/${urlEncodePath(deviceId)}"
        val item = request("GET", path, bearer = accessToken) as? JsonObject
        val role = item?.str("role")
        if (item == null || (role != "host" && role != "client")) {
            throw invalid("The server returned an invalid peer descriptor.")
        }
        val deviceIdValue = item.str("deviceId")
        val name = item.str("name")
        val platform = item.str("platform")
        val identityKey = item.str("identityKey")
        val membershipId = item.str("membershipId")
        if (deviceIdValue.isNullOrEmpty() || name.isNullOrEmpty() || platform == null ||
            identityKey.isNullOrEmpty() || membershipId.isNullOrEmpty()
        ) {
            throw invalid("The server returned an invalid peer descriptor.")
        }
        var presence = item
        if (item.booleanValue("online") == null) {
            val value = request("GET", "$path/presence", bearer = accessToken) as? JsonObject
            val online = value?.booleanValue("online")
            val presenceDevice = value.str("deviceId")
            if (online == null || (presenceDevice != null && presenceDevice != deviceId)) {
                throw invalid("The server returned invalid device presence.")
            }
            presence = value
        }
        val lastSeen = presence.intValue("lastSeenAt")
        return AuthorizedPeer(
            deviceId = deviceIdValue,
            name = name,
            role = role,
            platform = platform,
            identityKey = identityKey,
            membershipId = membershipId,
            online = presence.booleanValue("online"),
            lastSeenAt = lastSeen?.takeIf { it > 0 },
            clientVersion = item.str("clientVersion"),
            harnessVersion = item.str("harnessVersion"),
        )
    }

    /** `DELETE /api/v1/devices/self`：在中继侧吊销本机。 */
    suspend fun revokeSelf(accessToken: String) {
        request("DELETE", "/api/v1/devices/self", bearer = accessToken)
    }

    // -----------------------------------------------------------------------

    private suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        bearer: String? = null,
    ): JsonElement? {
        val url = serverUrl.newBuilder()
            .encodedPath(if (path.startsWith("/")) path.substringBefore('?') else "/$path")
            .apply {
                val query = path.substringAfter('?', "")
                if (query.isNotEmpty()) {
                    // 路径里的查询串已由调用方编码，这里原样透传（`addEncodedQueryParameter` 会二次编码）。
                    encodedQuery(query)
                }
            }
            .build()
        if (url.scheme != serverUrl.scheme || url.host != serverUrl.host || url.port != serverUrl.port) {
            throw invalid("Refusing a cross-origin Server request.")
        }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        val payload = body?.let { encodeJson(it).toRequestBody(JSON_MEDIA_TYPE) }
        val request = builder.method(method, payload ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(JSON_MEDIA_TYPE)).build()

        val status: Int
        val text: String
        try {
            // OkHttp 的 execute() 是阻塞调用。suspend 只说明「可以挂起」，不说明
            // 「阻塞在哪条线程」—— 不显式切 IO 的话，从 Compose 的
            // rememberCoroutineScope()（主线程）调用就会 NetworkOnMainThreadException。
            // 单测跑在 JVM 上不检查主线程限制，所以这个问题只在真机/模拟器上暴露。
            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    response.code to response.body?.string().orEmpty()
                }
            }
            status = code
            text = body
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw RemoteApiException("CONNECTION_FAILED", "Server request failed: ${e.message}", retryable = true)
        }

        var decoded: JsonElement? = null
        if (text.isNotEmpty()) {
            decoded = try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                null
            }
            if (decoded == null && status in 200..299) {
                throw RemoteApiException("INVALID_MESSAGE", "The server returned invalid JSON.", status)
            }
        }
        if (status in 200..299) return decoded?.takeUnless { it is JsonNull }

        val error = ((decoded as? JsonObject)?.get("error") as? JsonObject)
        val code = error.str("code")
            ?: (decoded as? JsonObject)?.str("code")
            ?: statusCode(status)
        val message = error.str("message")
            ?: (decoded as? JsonObject)?.str("detail")
            ?: "The server rejected the request."
        throw RemoteApiException(
            code, message,
            status = status,
            retryable = error.booleanValue("retryable") == true || status >= 500 || status == 429,
        )
    }

    private fun encodeJson(element: JsonElement): String = element.toString()

    private fun statusCode(status: Int): String = when (status) {
        401 -> "AUTH_INVALID"
        403 -> "AUTH_REQUIRED"
        404 -> "DEVICE_NOT_FOUND"
        409 -> "DEVICE_OWNERSHIP_REQUIRED"
        429 -> "RATE_LIMITED"
        else -> if (status >= 500) "CONNECTION_FAILED" else "INVALID_MESSAGE"
    }
}

/** 可空接收者的字符串取值：字段缺失、类型不对都返回 null。 */
private fun JsonObject?.str(key: String): String? = this?.stringValue(key)

/** 只接受 JSON 布尔；数字和字符串都不算（宽松转换会把 `"false"` 读成 true）。 */
internal fun JsonObject?.booleanValue(key: String): Boolean? {
    val primitive = this?.get(key) as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** 查询串里的游标按 RFC 3986 编码（空格 → %20，不是 `+`）。 */
private fun urlEncodeQuery(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** 路径段编码：`/` 要变 `%2F`，否则 deviceId 能穿越路径。 */
private fun urlEncodePath(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** 方便测试与日志：只暴露 origin，不带任何令牌。 */
internal fun HttpUrl.origin(): String =
    "$scheme://$host${if (port == HttpUrl.defaultPort(scheme)) "" else ":$port"}"

@Suppress("unused")
private fun JsonObject.objectValue(key: String): JsonObject? = this[key]?.jsonObject
