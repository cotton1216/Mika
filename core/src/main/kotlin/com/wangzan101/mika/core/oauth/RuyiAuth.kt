package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 一次如意登录的随机值:每次新生成。[redirectUri] 是浏览器授权后跳回如愿的 scheme 回调地址。 */
data class RuyiAttempt(val state: String, val verifier: String, val redirectUri: String) {
    val challenge: String get() = Pkce.challenge(verifier)
}

/** 换到的一把如意推理 Key。 */
data class RuyiKey(val id: Long, val value: String, val name: String)

/**
 * 用如意账号登录(AI-Server 桌面的同一套协议):在浏览器里打开授权页,用户在如意/统一账号里登录后,带着 `code`
 * 经 scheme 回调回到如愿,再用 `code` 和校验串换账号 JWT,列出账号下的推理 Key,取第一把 active 的。之后这把 Key
 * 和手动填 Key 的服务商一样用(列模型、调用都走 [API_BASE])。
 *
 * 回调地址是 [CALLBACK_URI](`ruyiai://auth2/callback`,须在 IAM 里登记),授权码经 URL 片段(`#code=…&state=…`)回传。
 * Key 不进日志;异常文字里只有类别和错误代码。
 */
class RuyiAuth(
    private val http: OkHttpClient,
    private val loginOrigin: String = LOGIN_ORIGIN,
    private val aiServerOrigin: String = AI_SERVER_ORIGIN,
    /** 登录、换令牌、列 Key 端点允许的主机。默认只有如意。测试里换成本机。 */
    private val siteHosts: Set<String> = setOf(HOST),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 一次登录的随机值:回调地址固定是 [CALLBACK_URI](浏览器授权后跳这个 scheme 唤起如愿)。 */
    fun newAttempt() = RuyiAttempt(Pkce.state(), Pkce.verifier(), CALLBACK_URI)

    /** 授权页地址:回调地址、S256 校验、state、client_id(白名单二元组的标识)。 */
    fun authorizeUrl(attempt: RuyiAttempt): String =
        OauthUrls.check(loginOrigin.trimEnd('/') + AUTHORIZE_PATH, siteHosts).newBuilder()
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", attempt.redirectUri)
            .addQueryParameter("state", attempt.state)
            .addQueryParameter("code_challenge", attempt.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build().toString()

    /**
     * 本机回调收不收这一次:state 对得上的收;没带 state、没带 code、带了 `error` 的(用户在授权页拒绝)也收,
     * 当作没同意。别的(带 code 却没有或对不上 state,或什么都没带)不收:不是这次发起的,回 400 接着等。
     */
    fun accepts(params: Map<String, String>, attempt: RuyiAttempt): Boolean {
        val state = params["state"]
        if (state != null) return state == attempt.state
        return params["code"].isNullOrEmpty() && !params["error"].isNullOrEmpty()
    }

    /** 从回调里取出 `code`;没有就是用户没同意(或授权页报了错)。 */
    fun readCode(params: Map<String, String>, attempt: RuyiAttempt): String {
        val state = params["state"]
        if (state != null && state != attempt.state) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        val code = params["code"]?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Denied, params["error"]?.take(40) ?: "no_code")
        if (state == null) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        return code
    }

    /** 用 `code` 和校验串换账号 JWT(`POST /api/v1/auth/desktop/exchange`),再列出账号下的 Key。 */
    suspend fun signIn(code: String, attempt: RuyiAttempt): RuyiKey {
        val account = exchange(code, attempt)
        val keys = listKeys(account.accessToken, account.userId)
        return keys.firstOrNull()
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_key")
    }

    /** 换账号 JWT。响应是 `{code:0, data:{access_token,…}}` 信封。出错只带回状态码,不带响应正文。 */
    private suspend fun exchange(code: String, attempt: RuyiAttempt): RuyiAccount {
        val url = OauthUrls.check(loginOrigin.trimEnd('/') + EXCHANGE_PATH, siteHosts)
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", attempt.verifier)
            put("redirect_uri", attempt.redirectUri)
            put("client_id", CLIENT_ID)
        }.toString().toRequestBody(JSON_TYPE)
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(url).header("Accept", "application/json").post(body).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            throw AccountSignInException(AccountSignInFailure.Network, "http", e)
        }
        when {
            raw.status == 400 -> throw AccountSignInException(AccountSignInFailure.Rejected, "http_400")
            raw.status == 401 || raw.status == 403 -> throw AccountSignInException(AccountSignInFailure.Expired, "http_${raw.status}")
            raw.status == 429 || raw.status in 500..599 -> throw AccountSignInException(AccountSignInFailure.Network, "http_${raw.status}")
            raw.status != 200 -> throw AccountSignInException(AccountSignInFailure.Rejected, "http_${raw.status}")
        }
        val data = envelope(raw.body)
        val access = data?.str("access_token")?.takeIf { it.isNotBlank() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_access_token")
        val user = data["user"] as? JsonObject
        val userId = user?.long("id")
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_user")
        return RuyiAccount(access, userId)
    }

    /** 列出账号下 active 的推理 Key(`GET /api/v1/keys`)。分页读,最多 [MAX_PAGES] 页。 */
    private suspend fun listKeys(accessToken: String, userId: Long): List<RuyiKey> {
        val all = mutableListOf<RuyiKey>()
        var page = 1
        var pages = 1
        while (page <= pages && page <= MAX_PAGES) {
            val url = OauthUrls.check(
                aiServerOrigin.trimEnd('/') + "/api/v1/keys?status=active&page=$page&page_size=100", siteHosts,
            )
            val raw = try {
                HttpSupport.execute(http, Request.Builder().url(url).header("Accept", "application/json")
                    .header("Authorization", "Bearer $accessToken").get().build())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelError) {
                throw AccountSignInException(AccountSignInFailure.Network, "http", e)
            }
            if (raw.status == 401 || raw.status == 403) throw AccountSignInException(AccountSignInFailure.Expired, "http_${raw.status}")
            if (raw.status != 200) throw AccountSignInException(AccountSignInFailure.Rejected, "http_${raw.status}")
            val data = envelope(raw.body) ?: throw AccountSignInException(AccountSignInFailure.Rejected, "bad_body")
            pages = data.int("pages") ?: pages
            val items = data["items"] as? JsonArray
                ?: throw AccountSignInException(AccountSignInFailure.Rejected, "bad_items")
            for (item in items) {
                val o = item as? JsonObject ?: continue
                if (o.str("status") != "active") continue
                val owner = o.long("user_id") ?: continue
                if (owner != userId) continue
                val key = o.str("key")?.takeIf { it.isNotBlank() } ?: continue
                all += RuyiKey(o.long("id") ?: 0L, key, o.str("name").orEmpty())
            }
            page++
        }
        return all
    }

    /** 解开 `{code:0, data:…}` 信封;code 不是 0 或解不开为 null。 */
    private fun envelope(body: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val code = (root["code"] as? JsonPrimitive)?.let { p -> p.contentOrNull?.toIntOrNull() ?: p.intOrNull } ?: return null
        if (code != 0) return null
        return root["data"] as? JsonObject
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull

    private data class RuyiAccount(val accessToken: String, val userId: Long)

    companion object {
        /** 登录、换令牌、列 Key 的站点(桌面部署的账号根)。 */
        const val LOGIN_ORIGIN = "https://ai.thchengtay.com/aiserver"
        const val AI_SERVER_ORIGIN = "https://ai.thchengtay.com/aiserver"
        const val HOST = "ai.thchengtay.com"
        /** 换到的 Key 调用的接口根(OpenAI 兼容)。 */
        const val API_BASE = "https://ai.thchengtay.com/aiserver/v1"
        /** 浏览器授权后跳回如愿的 scheme 回调地址:须和 IAM 里 aiserver client 登记的回调一致。 */
        const val CALLBACK_URI = "ruyiai://auth2/callback"
        /** 客户端标识(非凭据):与回调地址组成白名单二元组,服务端精确匹配。命名规范 `<产品>-<平台>`。 */
        const val CLIENT_ID = "ruyi-android"
        const val AUTHORIZE_PATH = "/desktop/authorize"
        const val EXCHANGE_PATH = "/api/v1/auth/desktop/exchange"
        const val KEYS_PAGE = "https://ai.thchengtay.com/aiserver/keys"
        private const val MAX_PAGES = 10
        private val JSON_TYPE = "application/json".toMediaType()
    }
}

/**
 * 走一遍如意登录:生成一次随机校验([auth.newAttempt])→ [openBrowser] 打开授权页 → [awaitCode] 等浏览器跳回
 * scheme 回调(授权码在 URL 片段里,由接收方直接读 fragment)→ 换账号 JWT、列 Key、取第一把。换 Key 这一步
 * (至多 [finishTimeoutMs])算入总时长;等回调至多 [timeoutMs]。失败抛 [AccountSignInException];取消照常往外抛。
 * 返回换到的推理 Key。
 */
suspend fun signInWithRuyi(
    auth: RuyiAuth,
    openBrowser: (String) -> Unit,
    awaitCode: suspend (RuyiAttempt, Long) -> Map<String, String>?,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
): RuyiKey {
    val attempt = auth.newAttempt()
    openBrowser(auth.authorizeUrl(attempt))
    val params = awaitCode(attempt, timeoutMs) ?: throw AccountSignInException(AccountSignInFailure.TimedOut)
    return try {
        withTimeout(finishTimeoutMs) { auth.signIn(auth.readCode(params, attempt), attempt) }
    } catch (e: TimeoutCancellationException) {
        throw AccountSignInException(AccountSignInFailure.Network, "timeout", e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AccountSignInException) {
        throw e
    } catch (e: Exception) {
        throw AccountSignInException(AccountSignInFailure.Network, cause = e)
    }
}
