package com.wangzan101.mika.settings

import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceAccessStore
import com.wangzan101.mika.core.device.DeviceCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class DeviceAccessConfig(val enabled: Boolean = false, val kind: CredentialKind? = null,
    val blocked: Boolean = false, val revision: String = "")

/** 全部设置加密原子写入。仅保留非秘密状态流,不缓存解密后的凭据。读不出时按关闭处理。 */
class DeviceAccessSettings(private val dir: File, private val cipher: KeyCipher) : DeviceAccessStore {
    private val file = File(dir, "device-access.bin")
    private val mutable = MutableStateFlow(runCatching { read()?.let(::configOf) }.getOrNull() ?: DeviceAccessConfig())
    val config = mutable.asStateFlow()
    override val enabled get() = config.value.enabled
    override val credentialConfigured get() = config.value.kind != null
    override val attemptBlocked get() = config.value.blocked

    @Synchronized fun setEnabled(on: Boolean) {
        try { update(config.value.copy(enabled = on)) }
        catch (e: Exception) {
            // 撤销许可时即使磁盘/密钥不可用也先在进程内关闭;不让保存错误留下可用的旧许可。
            if (!on) { mutable.value = config.value.copy(enabled = false); file.delete() }
            throw e
        }
    }

    @Synchronized fun setCredential(kind: CredentialKind, value: String) {
        val chars = value.toCharArray()
        try { require(DeviceCredential.valid(kind, chars)) { "Unsupported credential format" } } finally { chars.fill('\u0000') }
        save(config.value.copy(kind = kind, blocked = false, revision = UUID.randomUUID().toString()), value)
    }

    @Synchronized fun clearCredential() { save(config.value.copy(kind = null, blocked = false, revision = ""), null) }

    @Synchronized override fun credential(): DeviceCredential? = runCatching {
        val o = read() ?: return null
        val c = configOf(o)
        if (c != config.value) return null
        c.kind?.let { DeviceCredential(it, (o.getValue("credential") as JsonPrimitive).content.toCharArray(), c.revision) }
    }.getOrNull()

    @Synchronized override fun beginAttempt(credential: DeviceCredential): Boolean {
        if (!enabled || attemptBlocked || config.value.revision != credential.revision || config.value.kind != credential.kind) return false
        update(config.value.copy(blocked = true))
        return true
    }

    @Synchronized override fun completeAttempt(credential: DeviceCredential) {
        if (config.value.revision == credential.revision) update(config.value.copy(blocked = false))
    }

    private fun read(): JsonObject? {
        if (!file.isFile) return null
        val bytes = cipher.decrypt(file.readBytes())
        return try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject } finally { bytes.fill(0) }
    }

    private fun configOf(o: JsonObject): DeviceAccessConfig {
        val kind = (o["kind"] as? JsonPrimitive)?.content?.let(CredentialKind::valueOf)
        val value = (o["credential"] as? JsonPrimitive)?.content
        val chars = value?.toCharArray()
        try { check(kind == null && value == null || kind != null && chars != null && DeviceCredential.valid(kind, chars)) }
        finally { chars?.fill('\u0000') }
        val revision = (o["revision"] as? JsonPrimitive)?.content.orEmpty()
        check(kind == null || revision.isNotEmpty())
        return DeviceAccessConfig((o["enabled"] as? JsonPrimitive)?.booleanOrNull == true, kind,
            (o["blocked"] as? JsonPrimitive)?.booleanOrNull == true, revision)
    }

    private fun update(config: DeviceAccessConfig) {
        val c = credential()
        try {
            check(config.kind == null || c != null) { "Credential unavailable; configure it again on the phone" }
            save(config, c?.let { String(it.value) })
        } finally { c?.close() }
    }

    private fun save(config: DeviceAccessConfig, value: String?) {
        val bytes = buildJsonObject {
            put("enabled", config.enabled); put("blocked", config.blocked); put("revision", config.revision)
            config.kind?.let { put("kind", it.name); put("credential", value) }
        }.toString().toByteArray(Charsets.UTF_8)
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        try {
            tmp.writeBytes(cipher.encrypt(bytes))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            mutable.value = config
        } finally { bytes.fill(0); tmp.delete() }
    }
}
