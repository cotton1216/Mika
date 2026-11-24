package com.xxt.zl.ai.ruyiremote.auth

import android.util.Log
import com.xxt.zl.ai.ruyiremote.BuildConfig
import com.xxt.zl.ai.ruyiremote.net.normalizeServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 一次如意登录的随机值：每次新生成。[redirectUri] 是浏览器授权后跳回如意 AI 的 scheme 回调地址。 */
data class RuyiAttempt(val state: String, val verifier: String, val redirectUri: String) {
    val challenge: String get() = Pkce.challenge(verifier)
}

/**
 * 登录换到的**中继**账号会话。
 *
 * 这里拿到的**不是** AI-Server 的 access_token：授权码直接交给中继，由中继去 AI-Server
 * 换票、读 profile，再签发自己的会话令牌。中继的 `/api/v1/devices/register` 只认自己
 * 签发的会话，把一个 AI-Server 令牌当账号凭据递过去必然 `ACCOUNT_AUTH_REQUIRED`。
 *
 * [account] 是中继的账号键（`iam:<hash>`），设备归属按它算；[displayName] 只用于展示。
 */
data class RuyiAccount(
    val accountToken: String,
    val account: String,
    val displayName: String,
)

/**
 * 用如意账号登录：授权在 **AI-Server** 的桌面授权页完成，换票打的是**中继**。
 *
 * 两步在两个服务上，这是关键：
 *
 * 1. `GET {AI-Server}/desktop/authorize`（[LOGIN_ORIGIN]）—— 用户在系统浏览器里登录，
 *    授权码经自定义 scheme 回传。`client_id` 与回调地址是 AI-Server 白名单里的二元组。
 * 2. `POST {中继}/api/v1/auth/aiserver/exchange` —— 把 code 交给中继，由中继去换票。
 *    **请求体里没有 `client_id`**：中继按回调地址反查该用哪个客户端，所以手机上不必
 *    再抄一份 client_id，也不会出现「拿网页的 client_id 配手机回调」这种混搭。
 *
 * 早先的实现是自己打 AI-Server 的 `/desktop/exchange` 拿 access_token 当账号凭据，
 * 那条路在中继上走不通（中继只认自己的会话），故改成本实现。
 *
 * 回调地址 [CALLBACK_URI]（`ruyiai://auth3/callback`）必须在 AI-Server 登记，且中继的
 * `DSH_AISERVER_MOBILE_REDIRECT_URI` 要与它逐字一致。授权码可能在 URL **片段**里
 * （`#code=…&state=…`），所以读回调时 query 与 fragment 都要看。
 *
 * 令牌不进日志；异常里只有类别和错误代码。
 */
class RuyiAuth(
    private val http: OkHttpClient,
    /** 授权页所在服务；构建期可用 `-PruiyiLoginOrigin=` 换成自建 / 本地联调地址。 */
    private val loginOrigin: String = BuildConfig.RUYI_LOGIN_ORIGIN,
    /**
     * 授权页只允许这台主机，默认就取自 [loginOrigin] —— 地址可配，但请求不能
     * 被改道到别处（SSRF）。需要更严时可以显式收紧。
     */
    private val allowedHosts: Set<String> = setOfNotNull(loginOrigin.toHttpUrlOrNull()?.host),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun newAttempt() = RuyiAttempt(Pkce.state(), Pkce.verifier(), CALLBACK_URI)

    /** 授权页地址：回调地址、S256 校验、state、client_id（白名单二元组的标识）。 */
    fun authorizeUrl(attempt: RuyiAttempt): String =
        requireHost(loginOrigin.trimEnd('/') + AUTHORIZE_PATH).newBuilder()
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", attempt.redirectUri)
            .addQueryParameter("state", attempt.state)
            .addQueryParameter("code_challenge", attempt.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build().toString()

    /**
     * 这个回调收不收：state 对得上的收；没带 state、没带 code、带了 `error` 的
     * （用户在授权页拒绝）也收，当作没同意。带 code 却不带 state 的不收 ——
     * 那不是这次发起的登录。
     */
    fun accepts(params: Map<String, String>, attempt: RuyiAttempt): Boolean {
        val state = params["state"]
        if (state != null) return state == attempt.state
        return params["code"].isNullOrEmpty() && !params["error"].isNullOrEmpty()
    }

    /** 从回调里取出 `code`；没有就是用户没同意（或授权页报了错）。 */
    fun readCode(params: Map<String, String>, attempt: RuyiAttempt): String {
        val state = params["state"]
        if (state != null && state != attempt.state) throw SignInException(SignInFailure.Rejected, "state")
        val code = params["code"]?.takeIf { it.isNotEmpty() }
            ?: throw SignInException(SignInFailure.Denied, params["error"]?.take(40) ?: "no_code")
        if (state == null) throw SignInException(SignInFailure.Rejected, "state")
        return code
    }

    /**
     * 把授权码交给**中继**，换中继账号会话（`POST {relay}/api/v1/auth/aiserver/exchange`）。
     *
     * [relayBase] 是用户配置的中继地址，允许 https 或内网 http（与 [normalizeServerUrl]
     * 同一套规则），所以这里不套用授权页的主机白名单。
     */
    suspend fun exchange(code: String, attempt: RuyiAttempt, relayBase: String): RuyiAccount {
        // 中继地址是用户填的：不合法就当场说是地址问题，别退化成「网络不通」。
        val url = try {
            exchangeUrl(relayBase)
        } catch (e: IllegalArgumentException) {
            throw SignInException(SignInFailure.Rejected, "bad_relay", e)
        }
        val body = buildJsonObject {
            put("code", code)
            put("codeVerifier", attempt.verifier)
            put("redirectUri", attempt.redirectUri)
        }.toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(body)
            .build()
        // OkHttp 的 execute() 是阻塞调用：不切 IO 线程就会在主线程抛
        // NetworkOnMainThreadException（实测 15:20 就是这样，界面只会说「网络不通」，
        // 看起来像服务没起，其实一行网络包都没发出去）。
        val (status, text) = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { response ->
                    response.code to response.body?.string().orEmpty()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只记异常类别：响应体里有会话令牌，不进日志。
            Log.w(TAG, "exchange transport failed: ${e.javaClass.simpleName}")
            throw SignInException(SignInFailure.Network, "http", e)
        }
        when {
            // 404：这套中继没开如意账号登录（缺 DSH_AISERVER_* 或身份服务配置）。
            // 与「码不对」不是一回事，单独给码让界面说清楚。
            status == 404 -> throw SignInException(SignInFailure.Rejected, "relay_not_configured")
            // 400/401：码过期、被用过、PKCE 不匹配、回调不在白名单，都只意味着重新登录。
            status == 400 || status == 401 -> throw SignInException(SignInFailure.Expired, "http_$status")
            status == 429 -> throw SignInException(SignInFailure.Network, "http_429")
            status in 500..599 -> throw SignInException(SignInFailure.Network, "http_$status")
            status != 200 -> throw SignInException(SignInFailure.Rejected, "http_$status")
        }
        return relaySession(text) ?: throw SignInException(SignInFailure.Rejected, "bad_body")
    }

    /**
     * 解析中继的会话响应：`{account, displayName, token, expiresAt}`（无信封）。
     * 缺任何一个必需字段都算坏响应 —— 带着半截数据继续跑，只会变成更难查的错。
     */
    private fun relaySession(body: String): RuyiAccount? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val token = root.str("token")?.takeIf { it.isNotBlank() } ?: return null
        val account = root.str("account")?.takeIf { it.isNotBlank() } ?: return null
        val displayName = root.str("displayName")?.takeIf { it.isNotBlank() } ?: account
        return RuyiAccount(token, account, displayName)
    }

    /** 中继地址 → 换票端点。地址规范与 [normalizeServerUrl] 一致（https，或内网 http）。 */
    private fun exchangeUrl(relayBase: String): HttpUrl =
        normalizeServerUrl(relayBase)
            .newBuilder()
            .addPathSegments(RELAY_EXCHANGE_PATH.trim('/'))
            .build()

    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun requireHost(raw: String): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: throw SignInException(SignInFailure.Rejected, "bad_url")
        if (url.host !in allowedHosts) throw SignInException(SignInFailure.Rejected, "bad_host")
        return url
    }

    companion object {
        /** 浏览器授权后跳回应用的 scheme 回调：须与 AI-Server 白名单里 aiserver client 登记的完全一致。 */
        const val CALLBACK_URI = "ruyiai://auth3/callback"

        /** 客户端标识（非凭据）：与回调地址组成白名单二元组，服务端精确匹配。 */
        const val CLIENT_ID = "ruyi-android"

        const val AUTHORIZE_PATH = "/desktop/authorize"

        /** 换票在中继上，不在 AI-Server 上 —— 路径里带 aiserver 是因为「换的是 AI-Server 的票」。 */
        const val RELAY_EXCHANGE_PATH = "/api/v1/auth/aiserver/exchange"

        private const val TAG = "RuyiAuth"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
