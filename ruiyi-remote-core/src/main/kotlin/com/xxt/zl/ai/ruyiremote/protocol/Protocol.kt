package com.xxt.zl.ai.ruyiremote.protocol

/** 协议错误：携带稳定 code，供上层映射成 UI 文案。 */
class ProtocolError(message: String, val code: String? = null) : Exception(message)

/**
 * Remote 协议版本。与 Dart 侧 `protocolVersion` 必须一致；
 * 帧里的 `v` 不匹配一律 fail closed。
 */
const val PROTOCOL_VERSION = 1

/** Remote 消息超过这个大小就先切 DSHF 分片，再进 Noise。 */
const val SECURE_FRAGMENT_CHUNK_BYTES = 48 * 1024

/** 重组后允许的最大 Remote 消息。 */
const val MAX_SECURE_MESSAGE_BYTES = 4 * 1024 * 1024
