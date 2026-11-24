package com.xxt.zl.ai.ruyiremote.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * X25519 的最小封装。
 *
 * 为什么不用 JCA：`KeyAgreement.getInstance("X25519")` 要 API 33+，而本工程 minSdk 是 29。
 * Bouncy Castle 的 lightweight API 不依赖 provider 注册（Android 自带一份旧版 BC，
 * 注册会互相覆盖），直接在纯 Kotlin/Java 层算，行为跨 API 等级一致。
 *
 * 私钥按生成时的原样保存，**不预先 clamp** —— clamp 由标量乘内部完成，
 * 与 Dart 侧 `DartX25519` 的约定一致。
 */
object X25519 {
    const val KEY_BYTES = 32

    private val random = SecureRandom()

    /** 曲线的基点 u=9。 */
    private val basePoint = ByteArray(KEY_BYTES).also { it[0] = 9 }

    fun generatePrivateKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /** 由私钥推出公钥；[privateKey] 必须 32 字节。 */
    fun publicKeyOf(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_BYTES) { "X25519 private keys must be 32 bytes." }
        val parameters = X25519PrivateKeyParameters(privateKey, 0)
        return parameters.generatePublicKey().encoded
    }

    /**
     * X25519(k, u)。默认拒绝全零结果（低阶对端点），与 Dart 侧 `allowZero = false` 一致。
     */
    fun sharedSecret(
        privateKey: ByteArray,
        publicKey: ByteArray,
        allowZero: Boolean = false,
    ): ByteArray {
        if (privateKey.size != KEY_BYTES || publicKey.size != KEY_BYTES) {
            throw NoiseSessionError("INVALID_KEY", "X25519 keys must be 32 bytes.")
        }
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), secret, 0)
        if (!allowZero && secret.all { it == 0.toByte() }) {
            throw NoiseSessionError("INVALID_KEY", "X25519 produced a low-order shared secret.")
        }
        return secret
    }

    fun keyPairFromPrivateKey(privateKey: ByteArray): KeyPair {
        require(privateKey.size == KEY_BYTES) { "X25519 private keys must be 32 bytes." }
        return KeyPair(privateKey.copyOf(), publicKeyOf(privateKey))
    }

    fun generateKeyPair(): KeyPair = keyPairFromPrivateKey(generatePrivateKey())

    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
}
