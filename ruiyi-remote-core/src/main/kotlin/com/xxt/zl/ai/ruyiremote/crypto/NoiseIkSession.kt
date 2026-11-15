package com.xxt.zl.ai.ruyiremote.crypto

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

enum class NoiseRole { initiator, responder }

/**
 * 如意远程的 Noise 会话：只实现固定的 v1 IK 套件，且远端静态密钥必须与本地
 * 已固定的那把完全一致，之后才允许收发明文。
 *
 * IK 模式（`<- s` 是 pre-message，不是一条消息）：
 * ```
 * <- s
 * -> e, es, s, ss
 * <- e, ee, se
 * ```
 * 客户端是 initiator，Host 是 responder；生产环境两侧握手 payload 都为空。
 *
 * 逐行对照 Dart `crypto/noise_ik.dart` 的 `NoiseIkSession`。
 * `test` 里的 golden vectors 用来保证字节级等价。
 */
class NoiseIkSession(
    val role: NoiseRole,
    localPrivateKey: String,
    localPublicKey: String,
    remotePublicKey: String,
    prologue: ByteArray,
    ephemeralPrivateKey: ByteArray? = null,
) {
    private var expectedRemoteStatic: ByteArray = requireKey(remotePublicKey, "remote public key")
    private var ephemeralSeed: ByteArray? = ephemeralPrivateKey?.copyOf()
    private val staticKey: X25519.KeyPair
    private val symmetric = SymmetricState(NOISE_IK_PROTOCOL)
    private var ephemeral: X25519.KeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var remoteStaticKey: ByteArray? = null
    private var messageIndex = 0
    private var send: CipherState? = null
    private var receive: CipherState? = null
    private var destroyed = false

    init {
        val private = requireKey(localPrivateKey, "local private key")
        val public = requireKey(localPublicKey, "local public key")
        staticKey = X25519.keyPairFromPrivateKey(private)
        if (!constantTimeEquals(staticKey.publicKey, public)) {
            throw NoiseSessionError("INVALID_KEY", "The local public key does not match the private key.")
        }
        symmetric.mixHash(prologue)
        // Pre-message `<- s`：responder 的静态密钥双方都知道。
        symmetric.mixHash(if (role == NoiseRole.initiator) expectedRemoteStatic else staticKey.publicKey)
    }

    val protocol: String get() = NOISE_IK_PROTOCOL

    val complete: Boolean get() = send != null

    val isDestroyed: Boolean get() = destroyed

    /** 下一步握手消息是否该由我方写。 */
    val canWriteHandshake: Boolean
        get() {
            assertLive()
            return !complete && isWriteTurn
        }

    private val isWriteTurn: Boolean get() = (messageIndex == 0) == (role == NoiseRole.initiator)

    /** 写出一条握手消息（initiator 是第 1 条，responder 是第 2 条）。 */
    fun writeHandshake(payload: ByteArray = ByteArray(0)): ByteArray {
        assertHandshake(reading = false)
        try {
            val out = ByteArrayOutputStream()
            val ephemeralKey = (ephemeralSeed?.let { X25519.keyPairFromPrivateKey(it) }
                ?: X25519.generateKeyPair()).also { ephemeral = it }
            out.write(ephemeralKey.publicKey)
            symmetric.mixHash(ephemeralKey.publicKey)
            if (role == NoiseRole.initiator) {
                // -> e, es, s, ss
                symmetric.mixKey(X25519.sharedSecret(ephemeralKey.privateKey, expectedRemoteStatic))
                out.write(symmetric.encryptAndHash(staticKey.publicKey))
                symmetric.mixKey(X25519.sharedSecret(staticKey.privateKey, expectedRemoteStatic))
            } else {
                // <- e, ee, se
                symmetric.mixKey(X25519.sharedSecret(ephemeralKey.privateKey, remoteEphemeral!!))
                symmetric.mixKey(X25519.sharedSecret(ephemeralKey.privateKey, remoteStaticKey!!))
            }
            out.write(symmetric.encryptAndHash(payload))
            val message = out.toByteArray()
            if (message.size > MAX_NOISE_MESSAGE_BYTES) {
                throw NoiseSessionError("NOISE_MESSAGE_INVALID", "Noise handshake message is too large.")
            }
            messageIndex++
            finishIfReady()
            return message
        } catch (e: Throwable) {
            destroy()
            throw e
        }
    }

    /** 读入对端的握手消息，返回它的 payload。 */
    fun readHandshake(message: ByteArray): ByteArray {
        assertHandshake(reading = true)
        try {
            if (message.size > MAX_NOISE_MESSAGE_BYTES) {
                throw NoiseSessionError("NOISE_MESSAGE_INVALID", "Noise handshake message is too large.")
            }
            val minimum = if (role == NoiseRole.responder) {
                NOISE_DH_LEN + NOISE_DH_LEN + NOISE_TAG_BYTES + NOISE_TAG_BYTES
            } else {
                NOISE_DH_LEN + NOISE_TAG_BYTES
            }
            if (message.size < minimum) {
                throw NoiseSessionError("NOISE_MESSAGE_INVALID", "Noise handshake message is truncated.")
            }
            val theirEphemeral = message.copyOfRange(0, NOISE_DH_LEN).also { remoteEphemeral = it }
            symmetric.mixHash(theirEphemeral)
            var offset = NOISE_DH_LEN
            if (role == NoiseRole.responder) {
                // -> e, es, s, ss
                symmetric.mixKey(X25519.sharedSecret(staticKey.privateKey, theirEphemeral))
                val encryptedStatic = message.copyOfRange(offset, offset + NOISE_DH_LEN + NOISE_TAG_BYTES)
                offset += NOISE_DH_LEN + NOISE_TAG_BYTES
                val theirStatic = symmetric.decryptAndHash(encryptedStatic).also { remoteStaticKey = it }
                verifyRemoteStatic(theirStatic)
                symmetric.mixKey(X25519.sharedSecret(staticKey.privateKey, theirStatic))
            } else {
                // <- e, ee, se
                remoteStaticKey = expectedRemoteStatic
                symmetric.mixKey(X25519.sharedSecret(ephemeral!!.privateKey, theirEphemeral))
                symmetric.mixKey(X25519.sharedSecret(staticKey.privateKey, theirEphemeral))
            }
            val payload = symmetric.decryptAndHash(message.copyOfRange(offset, message.size))
            messageIndex++
            finishIfReady()
            return payload
        } catch (e: Throwable) {
            destroy()
            throw e
        }
    }

    /** 加密一条传输消息（明文 ≤ 65519 字节）。 */
    fun encrypt(plaintext: ByteArray): ByteArray {
        assertLive()
        val send = this.send
            ?: throw NoiseSessionError("NOISE_NOT_READY", "The Noise handshake is not complete.")
        if (plaintext.size + NOISE_TAG_BYTES > MAX_NOISE_MESSAGE_BYTES) {
            throw NoiseSessionError("NOISE_MESSAGE_INVALID", "Noise transport plaintext is too large.")
        }
        return send.encryptWithAd(ByteArray(0), plaintext)
    }

    /**
     * 解密下一条传输消息。重复、乱序、截断或伪造都会认证失败，
     * 随后会话被销毁（fail closed）。
     */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        assertLive()
        val receive = this.receive
            ?: throw NoiseSessionError("NOISE_NOT_READY", "The Noise handshake is not complete.")
        try {
            if (ciphertext.size > MAX_NOISE_MESSAGE_BYTES) {
                throw NoiseSessionError("NOISE_MESSAGE_INVALID", "Noise transport message is too large.")
            }
            return receive.decryptWithAd(ByteArray(0), ciphertext)
        } catch (e: Throwable) {
            destroy()
            throw e
        }
    }

    /** 我方下一条要发的消息的 nonce（握手完成后从 0 开始）。 */
    val sendingCounter: Long get() = send?.nonce ?: 0

    /** 下一条收到的消息应当携带的 nonce。 */
    val receivingCounter: Long get() = receive?.nonce ?: 0

    /** 握手中获知（或被确认）的远端静态密钥。 */
    val remoteStatic: ByteArray? get() = remoteStaticKey?.copyOf()

    fun destroy() {
        if (destroyed) return
        destroyed = true
        expectedRemoteStatic.fill(0)
        ephemeralSeed?.fill(0)
        staticKey.privateKey.fill(0)
        ephemeral?.privateKey?.fill(0)
        symmetric.destroy()
        send?.destroy()
        receive?.destroy()
        send = null
        receive = null
    }

    private fun finishIfReady() {
        if (messageIndex < 2) return
        verifyRemoteStatic(remoteStaticKey)
        val (c1, c2) = symmetric.split()
        if (role == NoiseRole.initiator) {
            send = c1
            receive = c2
        } else {
            send = c2
            receive = c1
        }
    }

    private fun verifyRemoteStatic(actual: ByteArray?) {
        if (actual == null || !constantTimeEquals(actual, expectedRemoteStatic)) {
            destroy()
            throw NoiseSessionError(
                "PEER_IDENTITY_MISMATCH",
                "The Noise peer static key does not match local trust.",
            )
        }
    }

    private fun assertHandshake(reading: Boolean) {
        assertLive()
        if (complete) {
            throw NoiseSessionError("NOISE_ALREADY_READY", "The Noise handshake is already complete.")
        }
        if (isWriteTurn == reading) {
            throw NoiseSessionError("NOISE_HANDSHAKE_ORDER", "The Noise handshake message arrived out of order.")
        }
    }

    private fun assertLive() {
        if (destroyed) throw NoiseSessionError("NOISE_DESTROYED", "The Noise session is closed.")
    }

    private companion object {
        fun requireKey(value: String, label: String): ByteArray {
            val key = try {
                Base64.getUrlDecoder().decode(value)
            } catch (e: IllegalArgumentException) {
                throw NoiseSessionError("INVALID_KEY", "$label is not base64url.")
            }
            if (key.size != 32) {
                throw NoiseSessionError("INVALID_KEY", "$label must be a 32-byte X25519 key.")
            }
            return key
        }
    }
}

/** 定长比较，避免按字节短路泄露差异位置。 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

/**
 * UTF-8 `DSH-REMOTE\0v=1\0connection=<id>\0host=<id>\0client=<id>`：
 * 把握手绑定到协议 v1、这条 Server 连接以及两端设备。
 */
fun createNoisePrologue(connectionId: String, hostDeviceId: String, clientDeviceId: String): ByteArray {
    for ((name, value) in listOf(
        "connectionId" to connectionId,
        "hostDeviceId" to hostDeviceId,
        "clientDeviceId" to clientDeviceId,
    )) {
        if (value.isEmpty() || value.contains('\u0000')) {
            throw IllegalArgumentException("$name is not safe for a Noise prologue: \"$value\"")
        }
    }
    return "DSH-REMOTE\u0000v=1\u0000connection=$connectionId\u0000host=$hostDeviceId\u0000client=$clientDeviceId"
        .toByteArray(Charsets.UTF_8)
}

/** 身份密钥的展示指纹：SHA-256 前 6 字节，12 个大写十六进制字符。 */
fun identityFingerprint(publicKeyBase64Url: String): String {
    val digest = sha256(Base64.getUrlDecoder().decode(publicKeyBase64Url))
    return digest.copyOfRange(0, 6).joinToString("") { "%02x".format(it) }.uppercase()
}

/** 便于念出来的分组：`ABCD EF01 2345`。 */
fun formatFingerprint(fingerprint: String): String =
    fingerprint.chunked(4).joinToString(" ")
