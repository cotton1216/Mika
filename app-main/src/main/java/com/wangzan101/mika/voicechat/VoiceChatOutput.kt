package com.wangzan101.mika.voicechat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.wangzan101.mika.calls.VolcRealtimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把 VolcEngine 返回的 PCM（24kHz / mono / 16bit）送进 AudioTrack 边收边播。
 * 插话打断时 [interrupt] 会立即暂停并刷新缓冲，新的 AI 音频帧到了再自动恢复。
 */
class VoiceChatOutput {

    private var track: AudioTrack? = null
    private val writeLock = Mutex()
    private var interrupted = false
    private var initialized = false

    /** 初始化 AudioTrack（懒加载，第一次 write 时才真正起播） */
    fun ensureInitialized(): Boolean {
        if (initialized) return track != null
        val sampleRate = VolcRealtimeClient.OUTPUT_SAMPLE_RATE // 24000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) return false
        val bufSize = maxOf(minBuf, sampleRate * 2) // 至少 2 秒缓冲

        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build(),
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (t == null) return false
        track = t
        initialized = true
        return true
    }

    /**
     * 写一帧 AI 音频。首次调用会自动 start() AudioTrack。
     * 插话打断期间（[interrupt] 被调用后）帧会被丢弃直到 [resumeAfterInterrupt] 为止。
     */
    suspend fun write(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        writeLock.withLock {
            if (interrupted) return@withLock // 被打断了，丢弃
            val t = track ?: return@withLock
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { t.play() }
            }
            // 可能还没 start 成功
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return@withLock
            runCatching { t.write(pcm, 0, pcm.size) }
        }
    }

    /** 插话打断：立即暂停 AudioTrack 并刷掉缓冲里还没播出去的 PCM */
    fun interrupt() {
        interrupted = true
        val t = track ?: return
        runCatching {
            t.pause()
            t.flush()
        }
    }

    /** VolcEngine 确认生成了新的 AI 回复后调，恢复播放状态 */
    suspend fun resumeAfterInterrupt() {
        writeLock.withLock {
            interrupted = false
            val t = track ?: return@withLock
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { t.play() }
            }
        }
    }

    fun release() {
        writeLock.tryLock()
        try {
            track?.let {
                runCatching { it.stop() }
                it.release()
            }
            track = null
            initialized = false
            interrupted = false
        } finally {
            if (writeLock.isLocked) writeLock.unlock()
        }
    }
}
