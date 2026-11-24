package com.xxt.zl.ai.ruyiremote.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Noise 单条传输消息上限（含 16 字节 tag）。 */
const val MAX_NOISE_MESSAGE_BYTES = 65535

internal const val NOISE_TAG_BYTES = 16
internal const val NOISE_DH_LEN = 32

/** Noise 协议名。长度 36 > 32，所以初始 h 取它的 SHA-256。 */
const val NOISE_IK_PROTOCOL = "Noise_IK_25519_ChaChaPoly_SHA256"

/**
 * 最高可用 nonce。2^64-1 在 Noise 规范里是保留值；Kotlin 的 Long 是 64 位有符号，
 * 所以在远未触顶前就停。
 */
private const val MAX_NONCE = 0x3fffffffffffffffL

internal fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

/**
 * Noise §4.3 的 `HKDF(chaining_key, input_key_material, 2)`。
 * 返回 (output1, output2)。
 */
internal fun hkdf2(chainingKey: ByteArray, inputKeyMaterial: ByteArray): Pair<ByteArray, ByteArray> {
    val tempKey = hmacSha256(chainingKey, inputKeyMaterial)
    val output1 = hmacSha256(tempKey, byteArrayOf(0x01))
    val output2 = hmacSha256(tempKey, output1 + byteArrayOf(0x02))
    return output1 to output2
}

/**
 * Noise §5.2 的 CipherState。没有密钥时是恒等变换（握手第一个 `e` 之前就是如此）；
 * 有密钥时做 ChaCha20-Poly1305，nonce 按 §4.2 的 96 位小端/大端混合编码。
 */
internal class CipherState(private var key: ByteArray? = null) {
    var nonce: Long = 0
        private set

    val hasKey: Boolean get() = key != null

    fun initializeKey(key: ByteArray) {
        this.key = key
        nonce = 0
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val key = this.key ?: return plaintext.copyOf()
        if (nonce >= MAX_NONCE) {
            throw NoiseSessionError("NOISE_NONCE_EXHAUSTED", "The Noise nonce space is exhausted.")
        }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            IvParameterSpec(nonceBytes(nonce)),
        )
        cipher.updateAAD(ad)
        val out = cipher.doFinal(plaintext)
        nonce++
        return out
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = this.key ?: return ciphertext.copyOf()
        if (ciphertext.size < NOISE_TAG_BYTES) {
            throw NoiseSessionError("NOISE_MESSAGE_INVALID", "Noise ciphertext is shorter than its tag.")
        }
        if (nonce >= MAX_NONCE) {
            throw NoiseSessionError("NOISE_NONCE_EXHAUSTED", "The Noise nonce space is exhausted.")
        }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            IvParameterSpec(nonceBytes(nonce)),
        )
        cipher.updateAAD(ad)
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw NoiseSessionError("NOISE_DECRYPT_FAILED", "Noise message authentication failed.")
        }
        nonce++
        return plaintext
    }

    fun destroy() {
        key?.fill(0)
        key = null
    }

    private companion object {
        /** Noise §4.2：4 字节零 | n 的低 32 位小端 | n 的高 32 位小端。 */
        fun nonceBytes(n: Long): ByteArray {
            val out = ByteArray(12)
            val low = (n and 0xffffffffL).toInt()
            val high = ((n ushr 32) and 0xffffffffL).toInt()
            out[4] = (low and 0xff).toByte()
            out[5] = ((low ushr 8) and 0xff).toByte()
            out[6] = ((low ushr 16) and 0xff).toByte()
            out[7] = ((low ushr 24) and 0xff).toByte()
            out[8] = (high and 0xff).toByte()
            out[9] = ((high ushr 8) and 0xff).toByte()
            out[10] = ((high ushr 16) and 0xff).toByte()
            out[11] = ((high ushr 24) and 0xff).toByte()
            return out
        }
    }
}

/** Noise §5.2 的 SymmetricState。 */
internal class SymmetricState(protocolName: String) {
    private var ck: ByteArray
    var h: ByteArray
        private set
    private val cipher = CipherState()

    init {
        val name = protocolName.toByteArray(Charsets.UTF_8)
        h = if (name.size <= 32) {
            ByteArray(32).also { System.arraycopy(name, 0, it, 0, name.size) }
        } else {
            sha256(name)
        }
        ck = h.copyOf()
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (newCk, tempKey) = hkdf2(ck, inputKeyMaterial)
        ck = newCk
        cipher.initializeKey(tempKey)
    }

    fun mixHash(data: ByteArray) {
        h = sha256(h + data)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encryptWithAd(h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = hkdf2(ck, ByteArray(0))
        return CipherState(k1) to CipherState(k2)
    }

    fun destroy() {
        ck.fill(0)
        cipher.destroy()
    }
}
