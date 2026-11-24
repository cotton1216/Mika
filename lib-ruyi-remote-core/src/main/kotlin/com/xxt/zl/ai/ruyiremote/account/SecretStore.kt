package com.xxt.zl.ai.ruyiremote.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 机密存储：设备私钥、令牌对、Host 的 pin 都走这里。
 *
 * 抽象成接口不是为了好看 —— 单测要能不碰 Android Keystore 就跑完整个账号流程，
 * 真机上换 [KeystoreSecretStore]。
 */
interface SecretStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun delete(key: String)
}

/** 单测用。 */
class InMemorySecretStore(initial: Map<String, String> = emptyMap()) : SecretStore {
    private val values = LinkedHashMap(initial)
    val snapshot: Map<String, String> get() = synchronized(values) { values.toMap() }

    override suspend fun read(key: String): String? = synchronized(values) { values[key] }
    override suspend fun write(key: String, value: String) {
        synchronized(values) { values[key] = value }
    }

    override suspend fun delete(key: String) {
        synchronized(values) { values.remove(key) }
    }
}

/**
 * 真机存储：SharedPreferences 存密文，密钥在 Android Keystore 里。
 *
 * 为什么不用 EncryptedSharedPreferences：那个库还在 alpha，且会把密钥库的
 * 兼容性问题一并引进来。这里只需要「一把不可导出的 AES-GCM 密钥 + 每条独立 IV」，
 * Keystore 原生支持，四十行写完，也没多一个依赖。
 *
 * 注意 Keystore 的密钥随「应用卸载 / 清除数据」一起消失，密文自然解不开 ——
 * 这正是想要的行为：重装后必须重新登录，旧令牌不会被新安装的库读到。
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("dsh_remote_secrets", Context.MODE_PRIVATE)

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key, null) ?: return@withContext null
        try {
            decrypt(stored)
        } catch (_: Exception) {
            // 密钥没了或密文损坏：当作没有，顺手清掉，避免每次读都炸一次。
            prefs.edit().remove(key).apply()
            null
        }
    }

    override suspend fun write(key: String, value: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(key, encrypt(value)).commit()
        }
    }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) { prefs.edit().remove(key).commit() }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    /** `v1:<b64(iv)>.<b64(ciphertext)>`。版本前缀留给将来换算法。 */
    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "v1:${b64(cipher.iv)}.${b64(ciphertext)}"
    }

    private fun decrypt(stored: String): String {
        require(stored.startsWith("v1:")) { "unknown secret format" }
        val (ivPart, dataPart) = stored.removePrefix("v1:").split(".", limit = 2)
            .also { require(it.size == 2) { "malformed secret" } }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(ivPart)))
        return String(cipher.doFinal(unb64(dataPart)), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dsh_remote_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
