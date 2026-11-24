package com.wangzan101.mika.voicechat

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机自己的麦克风和听筒,接成实时语音要的两根管子:
 * 上行 16 kHz 单声道 PCM16(只读,喂给实时语音),下行 24 kHz 单声道 PCM16(只写,实时语音往里写)。
 * 用管道而不是直接调音频接口,是为了复用代接电话那套 [com.wangzan101.mika.calls.CallPcmBridge]。
 */
internal class LiveVoiceAudio(private val audioManager: AudioManager) {

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var pipe: ParcelFileDescriptor? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private val closed = AtomicBoolean(true)

    /** 上行:写端给采集线程,读端给实时语音;读不到会一直等,所以采集一停就是 -1 结尾。 */
    lateinit var capture: InputStream
        private set

    /** 下行:写端给实时语音,读端给播放线程。 */
    lateinit var playback: OutputStream
        private set

    /** 本机实际可用的采样率,和实时语音协议要求的一致才算接通。 */
    val inputRate: Int get() = INPUT_RATE
    val outputRate: Int get() = OUTPUT_RATE

    /** 通话模式加免提:外放或听筒按 [speaker] 定;返回失败原因,成功为 null。 */
    @SuppressLint("MissingPermission")
    fun open(scope: CoroutineScope, speaker: Boolean): String? {
        close()
        val recordBuffer = AudioRecord.getMinBufferSize(
            INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (recordBuffer <= 0) return "手机不支持 16 kHz 录音"
        val mic = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                // 20 ms 一帧,留够抖动的余量
                maxOf(recordBuffer, INPUT_CHUNK * 8),
            )
        } catch (e: Exception) {
            return "没能打开麦克风:${e.message ?: e.javaClass.simpleName}"
        }
        if (mic.state != AudioRecord.STATE_INITIALIZED) {
            mic.release()
            return "麦克风被别的应用占用了"
        }
        val outBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (outBuffer <= 0) {
            mic.release()
            return "手机不支持 24 kHz 播放"
        }
        val player = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(outBuffer, OUTPUT_CHUNK * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            mic.release()
            return "没能打开扬声器:${e.message ?: e.javaClass.simpleName}"
        }
        val up = ParcelFileDescriptor.createPipe()
        val down = ParcelFileDescriptor.createPipe()
        capture = ParcelFileDescriptor.AutoCloseInputStream(up[0])
        val captureSink = ParcelFileDescriptor.AutoCloseOutputStream(up[1])
        val playSource = ParcelFileDescriptor.AutoCloseInputStream(down[0])
        playback = ParcelFileDescriptor.AutoCloseOutputStream(down[1])
        pipe = down[0]

        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speaker
        }
        recorder = mic
        track = player
        closed.set(false)
        runCatching { mic.startRecording() }
        runCatching { player.play() }

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(INPUT_CHUNK)
            try {
                while (isActive && !closed.get()) {
                    val read = mic.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        if (read < 0) break
                        continue
                    }
                    captureSink.write(buffer, 0, read)
                }
            } catch (_: Exception) {
                // 读端关了就是这一轮结束
            } finally {
                runCatching { captureSink.close() }
            }
        }
        playbackJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(OUTPUT_CHUNK)
            try {
                while (isActive && !closed.get()) {
                    val read = playSource.read(buffer)
                    if (read <= 0) break
                    var offset = 0
                    while (offset < read && isActive) {
                        val written = player.write(buffer, offset, read - offset)
                        if (written <= 0) break
                        offset += written
                    }
                }
            } catch (_: Exception) {
                // 写端关了
            } finally {
                runCatching { playSource.close() }
            }
        }
        return null
    }

    /** 打断朗读:把还没播出来的声音丢掉,后面写进来的继续播。 */
    fun flushPlayback() {
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.play() }
    }

    fun setSpeaker(on: Boolean) {
        runCatching { audioManager.isSpeakerphoneOn = on }
    }

    fun close() {
        closed.set(true)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        recorder = null
        track = null
        runCatching { pipe?.close() }
        pipe = null
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
        runCatching { audioManager.isSpeakerphoneOn = false }
        val jobs = listOfNotNull(captureJob, playbackJob)
        captureJob = null
        playbackJob = null
        jobs.forEach { it.cancel() }
    }

    suspend fun closeAndJoin() {
        val jobs = listOfNotNull(captureJob, playbackJob)
        close()
        jobs.forEach { runCatching { it.cancelAndJoin() } }
    }

    companion object {
        const val INPUT_RATE = 16_000
        const val OUTPUT_RATE = 24_000
        private const val INPUT_CHUNK = 640 // 20 ms 的 16 kHz 单声道 s16le
        private const val OUTPUT_CHUNK = 960 // 20 ms 的 24 kHz 单声道 s16le
    }
}
