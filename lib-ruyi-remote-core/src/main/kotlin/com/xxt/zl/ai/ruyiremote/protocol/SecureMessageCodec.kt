package com.xxt.zl.ai.ruyiremote.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 在 Noise 加密前切分明文、解密后重组。
 *
 * Noise 单条传输消息上限 65535 字节，所以大于 48 KiB 的消息以 DSHF 分片传输：
 *
 * ```
 * DSHF | version u8 (1) | messageId u32 | index u16 | total u16 |
 * totalBytes u32 | ≤ 48 KiB chunk
 * ```
 *
 * 全部大端。同一条消息的分片在一个已认证通道内连续、有序到达；任何重复、缺口、
 * 长度不符或超限都抛异常（调用方应关闭通道）。逐行对照 Dart
 * `protocol/messages.dart` 的 `SecureMessageCodec`。
 */
class SecureMessageCodec {
    private var nextMessageId = 1
    private val assemblies = HashMap<Int, Assembly>()

    private class Assembly(val total: Int, val totalBytes: Int) {
        val bytes = ByteArrayOutputStream()
        var received = 0
    }

    /** 返回要交给加密层的帧；未分片时就是原消息本身。 */
    fun encode(message: ByteArray): List<ByteArray> {
        if (message.size > MAX_SECURE_MESSAGE_BYTES) {
            throw ProtocolError("Secure message exceeds the reassembly limit.", "FRAME_TOO_LARGE")
        }
        if (message.size <= SECURE_FRAGMENT_CHUNK_BYTES) return listOf(message)

        val messageId = nextMessageId
        nextMessageId = if (messageId == -1) 1 else messageId + 1   // -1 == 0xffffffff（有符号）
        val total = (message.size + SECURE_FRAGMENT_CHUNK_BYTES - 1) / SECURE_FRAGMENT_CHUNK_BYTES
        val frames = ArrayList<ByteArray>(total)
        for (index in 0 until total) {
            val start = index * SECURE_FRAGMENT_CHUNK_BYTES
            val end = minOf(start + SECURE_FRAGMENT_CHUNK_BYTES, message.size)
            val chunk = end - start
            val frame = ByteArray(FRAGMENT_HEADER_BYTES + chunk)
            frame[0] = FRAGMENT_MAGIC[0]
            frame[1] = FRAGMENT_MAGIC[1]
            frame[2] = FRAGMENT_MAGIC[2]
            frame[3] = FRAGMENT_MAGIC[3]
            val view = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            view.put(4, FRAGMENT_VERSION.toByte())
            view.putInt(5, messageId)
            view.putShort(9, index.toShort())
            view.putShort(11, total.toShort())
            view.putInt(13, message.size)
            System.arraycopy(message, start, frame, FRAGMENT_HEADER_BYTES, chunk)
            frames.add(frame)
        }
        return frames
    }

    /** 返回完整消息；分片消息还没收齐时返回 null。 */
    fun decode(frame: ByteArray): ByteArray? {
        if (!isFragment(frame)) return frame
        if (frame.size < FRAGMENT_HEADER_BYTES || frame[4].toInt() != FRAGMENT_VERSION) {
            throw ProtocolError("Secure fragment header is invalid.")
        }
        val view = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val messageId = view.getInt(5)
        val index = view.getShort(9).toInt() and 0xffff
        val total = view.getShort(11).toInt() and 0xffff
        val totalBytes = view.getInt(13)
        if (messageId == 0 ||
            total < 2 ||
            index >= total ||
            totalBytes <= SECURE_FRAGMENT_CHUNK_BYTES ||
            totalBytes > MAX_SECURE_MESSAGE_BYTES ||
            total != (totalBytes + SECURE_FRAGMENT_CHUNK_BYTES - 1) / SECURE_FRAGMENT_CHUNK_BYTES
        ) {
            throw ProtocolError("Secure fragment metadata is invalid.")
        }
        val remaining = totalBytes - index * SECURE_FRAGMENT_CHUNK_BYTES
        val expected = minOf(remaining, SECURE_FRAGMENT_CHUNK_BYTES)
        val chunkLength = frame.size - FRAGMENT_HEADER_BYTES
        if (chunkLength != expected) throw ProtocolError("Secure fragment length is invalid.")

        var assembly = assemblies[messageId]
        if (assembly == null) {
            if (index != 0 || assemblies.size >= MAX_IN_FLIGHT_MESSAGES) {
                throw ProtocolError("Secure fragment sequence is invalid.")
            }
            assembly = Assembly(total, totalBytes)
            assemblies[messageId] = assembly
        }
        val chunksSoFar = if (assembly.received == 0) {
            0
        } else {
            (assembly.received + SECURE_FRAGMENT_CHUNK_BYTES - 1) / SECURE_FRAGMENT_CHUNK_BYTES
        }
        if (assembly.total != total || assembly.totalBytes != totalBytes || index != chunksSoFar) {
            assemblies.remove(messageId)
            throw ProtocolError("Secure fragment sequence is invalid.")
        }
        assembly.bytes.write(frame, FRAGMENT_HEADER_BYTES, chunkLength)
        assembly.received += chunkLength
        if (index + 1 < total) return null
        assemblies.remove(messageId)
        if (assembly.received != totalBytes) {
            throw ProtocolError("Secure message length is invalid.")
        }
        return assembly.bytes.toByteArray()
    }

    fun reset() {
        nextMessageId = 1
        assemblies.clear()
    }

    private fun isFragment(frame: ByteArray): Boolean {
        if (frame.size < FRAGMENT_MAGIC.size) return false
        for (i in FRAGMENT_MAGIC.indices) {
            if (frame[i] != FRAGMENT_MAGIC[i]) return false
        }
        return true
    }

    private companion object {
        const val FRAGMENT_HEADER_BYTES = 17
        const val FRAGMENT_VERSION = 1
        const val MAX_IN_FLIGHT_MESSAGES = 8
        val FRAGMENT_MAGIC = byteArrayOf(0x44, 0x53, 0x48, 0x46)   // DSHF
    }
}
