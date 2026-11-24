package com.wangzan101.mika.voicechat

import kotlin.math.PI
import kotlin.math.sin

/**
 * 思考提示音（thinking cue）：AI 在 THINKING 阶段播放的短促提示音，告诉用户"我还在听/想，别着急"。
 * MVP 版：24kHz / mono / 16bit 的 80ms 正弦波（440Hz），播放一次即丢弃。
 *
 * Phase 2：换成更友好的两音组合（哔-嘟），并在 VoiceChatController 的 THINKING 阶段真正调用。
 */
object ThinkingCue {

    private const val SAMPLE_RATE = 24_000
    private const val DURATION_MS = 80
    private const val FREQ = 440.0
    private const val AMPLITUDE = 0.3

    /** 生成并返回一段 PCM 提示音。每次调用返回新的 ByteArray。 */
    fun generate(): ByteArray {
        val samples = SAMPLE_RATE * DURATION_MS / 1000
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            val s = (AMPLITUDE * 32767.0 * sin(2.0 * PI * FREQ * t)).toInt().toShort()
            bytes[i * 2] = (s.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
