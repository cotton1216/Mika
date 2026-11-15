package com.xxt.zl.ai.ruyiremote.auth

/** 登录失败类别。UI 文案按类别映射，不展示服务端原文。 */
enum class SignInFailure {
    /** 服务端拒绝（state 不符、没有 code、400 等）。 */
    Rejected,

    /** 用户在授权页拒绝，或授权页报错。 */
    Denied,

    /** 授权码已过期/已被用过（401、403）。 */
    Expired,

    /** 网络、限流、5xx。可重试。 */
    Network,

    /** 等浏览器回跳超时。 */
    TimedOut,
}

class SignInException(
    val failure: SignInFailure,
    /** 诊断用短码（`http_400`、`state`、`no_code`…），不含令牌或授权码。 */
    val code: String? = null,
    cause: Throwable? = null,
) : Exception("${failure.name}${code?.let { " ($it)" } ?: ""}", cause)
