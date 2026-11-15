package com.xxt.zl.ai.ruyiremote.crypto

/**
 * Noise 会话错误。code 取值与 Dart 侧 `NoiseSessionError.code` 一致，
 * 便于两端日志对照：`INVALID_KEY`、`NOISE_HANDSHAKE_ORDER`、`NOISE_ALREADY_READY`、
 * `NOISE_NOT_READY`、`NOISE_DESTROYED`、`NOISE_DECRYPT_FAILED`、`NOISE_MESSAGE_INVALID`、
 * `NOISE_NONCE_EXHAUSTED`、`PEER_IDENTITY_MISMATCH`。
 */
class NoiseSessionError(val code: String, message: String) : Exception("NoiseSessionError($code): $message")
