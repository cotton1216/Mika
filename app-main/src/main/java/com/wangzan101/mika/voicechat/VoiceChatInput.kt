package com.wangzan101.mika.voicechat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.wangzan101.mika.calls.VolcRealtimeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 麦克风 PCM 采集：AudioRecord → CallRealtimeSession.sendPcm16()。
 * 固定配置：16kHz / mono / 16bit PCM，每帧 640 字节（20ms）——完全对齐 VolcEngine INPUT_RATE 和 CHUNK_BYTES。
 */
class VoiceChatInput(private val context: Context) {

    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _micLevel = kotlinx.coroutines.flow.MutableStateFlow(0f) // 0.0 ~ 1.0
    val micLevel: kotlinx.coroutines.flow.StateFlow<Float> = _micLevel

    /** RECORD_AUDIO 权限是否已授权 */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 启动采集并把每帧 PCM 送进 [onFrame]。
     * @param onFrame 每收到 640 字节就调用一次（20ms 节奏）。
     * @param onUserSpeaking 检测到持续人声时回调 true；持续安静时回调 false。
     */
    suspend fun start(
        onFrame: suspend (pcm: ByteArray, size: Int) -> Boolean,
        onUserSpeaking: (Boolean) -> Unit,
    ): Boolean {
        if (!hasPermission()) return false
        if (!running.compareAndSet(false, true)) return true

        val sampleRate = VolcRealtimeClient.INPUT_SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = maxOf(minBuf, VolcRealtimeClient.INPUT_CHUNK_BYTES * 4)

        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufSize,
            )
        }.getOrNull() ?: run {
            running.set(false)
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            running.set(false)
            return false
        }

        recorder = rec
        rec.startRecording()

        readerJob = scope.launch {
            val frame = ByteArray(VolcRealtimeClient.INPUT_CHUNK_BYTES) // 640 bytes
            var speakingFrames = 0
            var quietFrames = 0
            try {
                while (this.isActive && running.get()) {
                    val read = rec.read(frame, 0, frame.size)
                    if (read <= 0) continue

                    // 计算音量（RMS）
                    var sum = 0.0
                    var i = 0
                    while (i < read - 1) {
                        val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort().toDouble()
                        sum += sample * sample
                        i += 2
                    }
                    val rms = kotlin.math.sqrt(sum / (read / 2)) / 32768.0
                    _micLevel.value = rms.coerceIn(0.0, 1.0).toFloat()

                    // 简单人声检测：RMS > 0.01 算在说话
                    val talking = rms > 0.01
                    if (talking) {
                        speakingFrames++
                        quietFrames = 0
                        if (speakingFrames == 2) onUserSpeaking(true)
                    } else {
                        quietFrames++
                        speakingFrames = 0
                        if (quietFrames == 8) onUserSpeaking(false)
                    }

                    if (!onFrame(frame, read)) break
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                if (recorder === rec) recorder = null
                running.set(false)
                _micLevel.value = 0f
            }
        }
        return true
    }

    suspend fun stop() {
        running.set(false)
        readerJob?.let { withContext(Dispatchers.Default) { it.join() } }
        readerJob = null
    }

    val sampleRate: Int get() = VolcRealtimeClient.INPUT_SAMPLE_RATE
}
