package com.xxt.zl.ai.ruyiremote.session

import com.xxt.zl.ai.ruyiremote.channel.HarnessTransport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.UUID

const val MAX_FILE_BYTES = 20 * 1024 * 1024
// 当前 HarnessTunnel 未实现一元 transfer；给 4 MiB 加密消息保留 JSON 与文本空间。
const val MAX_PROMPT_IMAGE_BYTES = 2 * 1024 * 1024
const val MAX_PREVIEW_IMAGE_BYTES = 8 * 1024 * 1024
const val MAX_PROMPT_IMAGES = 4
val IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

data class PromptImage(val name: String, val mediaType: String, val data: String, val bytes: Int) {
    fun preview() = MessageImage(name = name, mediaType = mediaType, data = data)
}

data class MessageImage(
    val name: String = "图片",
    val mediaType: String? = null,
    val data: String? = null,
    val attachmentId: String? = null,
    val attachment: JsonObject? = null,
)

data class SelectedFile(val name: String, val bytes: ByteArray)
data class UploadedFile(val name: String, val path: String, val bytes: Int)
class AttachmentException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun readAttachmentBytes(input: InputStream, limit: Int): ByteArray {
    require(limit in 1..MAX_FILE_BYTES)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        if (count == 0) {
            val single = input.read()
            if (single < 0) break
            if (output.size() == limit) throw AttachmentException("附件超过大小限制")
            output.write(single)
            continue
        }
        if (count > limit - output.size()) throw AttachmentException("附件超过大小限制")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

fun validAttachmentName(name: String): Boolean = name.isNotBlank() && name.toByteArray().size <= 220 &&
    name != "." && name != ".." && !Regex("[/\\\\\\x00-\\x1f\\x7f<>:\"|?*]").containsMatchIn(name) &&
    !name.endsWith('.') && !name.endsWith(' ') && !name.startsWith(".dsh-upload-") &&
    !Regex("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\.|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)

fun imageMediaType(bytes: ByteArray): String? {
    fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 255) == values[it] }
    return when {
        starts(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
        starts(255, 216, 255) -> "image/jpeg"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}

fun decodeImageData(data: String, limit: Int = MAX_PREVIEW_IMAGE_BYTES): ByteArray {
    if (data.length > ((limit + 2L) / 3 * 4)) throw AttachmentException("图片超过预览大小限制")
    val bytes = try { Base64.getDecoder().decode(data) } catch (error: IllegalArgumentException) {
        throw AttachmentException("图片数据无效", error)
    }
    if (bytes.isEmpty() || bytes.size > limit || imageMediaType(bytes) == null) throw AttachmentException("图片格式或大小不受支持")
    return bytes
}

fun promptContent(text: String, images: List<PromptImage> = emptyList()): JsonArray {
    if (images.size > MAX_PROMPT_IMAGES) throw AttachmentException("每次最多发送 4 张图片")
    var total = 0L
    images.forEach {
        if (it.mediaType !in IMAGE_MEDIA_TYPES) throw AttachmentException("不支持此图片格式")
        val bytes = decodeImageData(it.data, MAX_PROMPT_IMAGE_BYTES)
        if (imageMediaType(bytes) != it.mediaType || bytes.size != it.bytes) throw AttachmentException("图片数据与元信息不一致")
        total += bytes.size
    }
    if (total > MAX_PROMPT_IMAGE_BYTES) throw AttachmentException("当前通道每条消息的图片合计不能超过 2 MiB")
    val content = buildJsonArray {
        if (text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", text) })
        images.forEach { image -> add(buildJsonObject {
            put("type", "image"); put("mediaType", image.mediaType); put("data", image.data); put("name", image.name)
        }) }
    }
    if (images.isNotEmpty() && content.toString().toByteArray().size > 3 * 1024 * 1024) {
        throw AttachmentException("文字与图片合计过大，请拆分发送")
    }
    return content
}

/** 只读取协议图片块；绝不把 Host 提供的 URL 交给携带凭据的 HTTP 客户端。 */
fun messageImages(message: JsonObject): List<MessageImage> {
    val content = message["content"] as? JsonArray ?: return emptyList()
    return content.flatMap { element ->
        val part = element as? JsonObject
        if (part?.attachmentText("type") == "tool-result") (part["content"] as? JsonArray)?.toList().orEmpty() else listOf(element)
    }.mapNotNull { element ->
        val part = element as? JsonObject ?: return@mapNotNull null
        if (part.attachmentText("type") != "image") return@mapNotNull null
        val ref = part["attachment"] as? JsonObject
        MessageImage(
            name = part.attachmentText("name") ?: ref?.attachmentText("name") ?: ref?.attachmentText("fileName") ?: "图片",
            mediaType = part.attachmentText("mediaType") ?: ref?.attachmentText("mediaType") ?: ref?.attachmentText("mimeType"),
            data = part.attachmentText("data"),
            attachmentId = part.attachmentText("attachmentId") ?: ref?.attachmentText("attachmentId"),
            attachment = ref,
        )
    }
}

suspend fun loadSessionImage(transport: HarnessTransport, sessionId: String, image: MessageImage): ByteArray {
    image.data?.let { return decodeImageData(it) }
    if (image.attachmentId == null && image.attachment == null) throw AttachmentException("此图片没有可安全读取的会话附件引用")
    val result = transport.call("session/attachment", buildJsonObject {
        put("request", buildJsonObject {
            put("sessionId", sessionId)
            image.attachmentId?.let { put("attachmentId", it) }
            image.attachment?.let { put("attachment", it) }
        })
    }) as? JsonObject ?: throw AttachmentException("Host 未返回图片数据")
    return decodeImageData(result.attachmentText("data") ?: throw AttachmentException("图片已失效或不可读取"))
}

fun uploadedFilesPrompt(text: String, files: List<UploadedFile>): String {
    if (files.isEmpty()) return text
    val refs = files.joinToString(" ") { file ->
        val path = file.path.removePrefix("./")
        if (path.any { it.isWhitespace() || it == '"' }) "@\"${path.replace("\"", "\\\"")}\"" else "@$path"
    }
    return listOf(text.trimEnd(), refs).filter { it.isNotEmpty() }.joinToString("\n\n")
}

/** rpc 必须是当前 HostConnection 的加密 RPC，不是 HarnessTransport.call。 */
class WorkspaceUploader(
    private val capabilities: List<String>,
    private val rpc: suspend (String, JsonObject) -> JsonElement?,
) {
    val supported get() = "workspace.upload.v1" in capabilities

    suspend fun upload(sessionId: String, file: SelectedFile, onProgress: (Int, Int) -> Unit = { _, _ -> }): UploadedFile {
        if (!supported) throw AttachmentException("此 Host 未声明工作区上传能力")
        if (sessionId.isBlank() || !validAttachmentName(file.name)) throw AttachmentException("文件名不受支持，请重命名后选择")
        if (file.bytes.size > MAX_FILE_BYTES) throw AttachmentException("单个文件不能超过 20 MiB")
        val id = UUID.randomUUID().toString()
        val scope = buildJsonObject { put("sessionId", sessionId); put("uploadId", id) }
        var committing = false
        try {
            currentCoroutineContext().ensureActive()
            val opened = rpc("workspace.upload.open", JsonObject(scope + mapOf("name" to JsonPrimitive(file.name), "totalBytes" to JsonPrimitive(file.bytes.size)))) as? JsonObject
            val chunk = opened?.number("chunkBytes")
            if (opened?.attachmentText("uploadId") != id || chunk == null || chunk !in 1..65536) throw AttachmentException("Host 返回了无效的上传分块参数")
            var offset = 0
            while (offset < file.bytes.size) {
                currentCoroutineContext().ensureActive()
                val next = minOf(offset + chunk, file.bytes.size)
                val response = rpc("workspace.upload.chunk", JsonObject(scope + mapOf(
                    "offset" to JsonPrimitive(offset),
                    "data" to JsonPrimitive(Base64.getEncoder().encodeToString(file.bytes.copyOfRange(offset, next))),
                ))) as? JsonObject
                if (response?.attachmentText("uploadId") != id || response?.number("receivedBytes") != next) throw AttachmentException("Host 上传进度确认不一致")
                offset = next
                onProgress(offset, file.bytes.size)
            }
            currentCoroutineContext().ensureActive()
            committing = true
            val response = rpc("workspace.upload.commit", scope) as? JsonObject
            val name = response?.attachmentText("name")
            val path = response?.attachmentText("path")
            if (name == null || !validAttachmentName(name) || path != "./$name" || response?.number("bytes") != file.bytes.size) {
                throw AttachmentException("Host 返回了无效的文件提交结果")
            }
            return UploadedFile(name, path, file.bytes.size)
        } catch (error: Exception) {
            withContext(NonCancellable) { withTimeoutOrNull(5000) { runCatching { rpc("workspace.upload.abort", scope) } } }
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (committing) throw AttachmentException("上传提交结果未确认，请先检查工作区，避免重复上传", error)
            throw AttachmentException("上传失败：${error.message ?: "连接中断"}", error)
        }
    }
}

private fun JsonObject.attachmentText(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
