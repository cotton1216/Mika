package com.xxt.zl.ai.ruyiremote.transport

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局共享的 OkHttp 客户端。
 *
 * 三处刻意设置：
 * * `pingInterval(0)` —— 关掉 WebSocket **协议层** ping。本协议有自己的
 *   `ping` / `pong` 控制帧，并且靠「3 个心跳周期无帧」判死；再叠一层 WS ping
 *   会掩盖真实静默，也会在两端都发，纯属噪声。
 * * `connectTimeout` / `readTimeout` 给足：WebSocket 是长连接，读超时不能按请求
 *   语义设短（读超时由 [RelayTransport] 的心跳看门狗负责）。
 * * `retryOnConnectionFailure(true)` 默认开着：只影响 TCP 建链，不影响帧语义。
 */
internal val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
