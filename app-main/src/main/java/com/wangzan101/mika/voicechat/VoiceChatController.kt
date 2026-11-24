package com.wangzan101.mika.voicechat

import android.content.Context
import android.media.AudioManager
import com.wangzan101.mika.calls.CallMediaFailure
import com.wangzan101.mika.calls.CallPlaybackQueue
import com.wangzan101.mika.calls.CallRealtimeSession
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.calls.VolcRealtimeEvent
import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.calls.VolcRealtimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * 实时语音对话:麦克风音频直接上行给豆包实时语音,它边想边说,声音直接播出来;
 * 它说话时你一开口就会被识别成打断,没播完的那半句立刻停。
 *
 * 这条链路和聊天里的助手没有关系:它不会看到聊天记录,也不会替你操作手机。
 */
class VoiceChatController(
    context: Context,
    private val profile: CallVoiceProfile,
    private val credentials: VolcRealtimeCredentials,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val audio = LiveVoiceAudio(audioManager)

    private val _state = MutableStateFlow(VoiceChatState())
    val state: StateFlow<VoiceChatState> = _state.asStateFlow()

    private val _speaker = MutableStateFlow(true)
    /** 免提开着:外放。关掉就走听筒。 */
    val speaker: StateFlow<Boolean> = _speaker.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private var session: CallRealtimeSession? = null
    private var jobs: List<Job> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 连上并开始收发;失败时返回原因,成功返回 null。 */
    suspend fun start(): String? {
        update { it.copy(phase = VoiceChatPhase.STARTING, endReason = null, errorDetail = null) }
        val audioError = audio.open(scope, _speaker.value)
        if (audioError != null) {
            update { it.copy(phase = VoiceChatPhase.ENDED, endReason = VoiceChatEnd.AUDIO_FAILED, errorDetail = audioError) }
            return audioError
        }
        val opened = try {
            VolcRealtimeProvider().connect(profile, credentials, SYSTEM_PROMPT)
        } catch (e: CancellationException) {
            audio.close()
            throw e
        } catch (e: Exception) {
            audio.close()
            val detail = describe(e)
            update { it.copy(phase = VoiceChatPhase.ENDED, endReason = VoiceChatEnd.CONNECT_FAILED, errorDetail = detail) }
            return detail
        }
        session = opened
        update { it.copy(phase = VoiceChatPhase.LISTENING) }
        jobs = startPumps(opened)
        return null
    }

    private fun startPumps(opened: CallRealtimeSession): List<Job> {
        val capture = scope.launch(Dispatchers.IO) {
            val frame = ByteArray(opened.inputChunkBytes)
            var position = 0
            try {
                while (true) {
                    val read = audio.capture.read(frame, position, frame.size - position)
                    if (read < 0) break
                    if (read == 0) continue
                    position += read
                    if (position == frame.size) {
                        // 麦克风始终上送:静音静的是如意 AI 的喇叭,不是你的麦
                        if (!opened.sendPcm16(frame)) break
                        position = 0
                    }
                }
            } catch (_: IOException) {
                // 关闭管道就是收尾
            }
            fail("音频上行断了")
        }
        val events = scope.launch { pumpEvents(opened) }
        return listOf(capture, events)
    }

    /** 收事件:音频进播放队列,字幕进界面,打断就把没播完的丢掉。 */
    private suspend fun pumpEvents(opened: CallRealtimeSession) = coroutineScope {
        val queue = CallPlaybackQueue()
        val writerLock = Mutex()
        val writer = launch(Dispatchers.IO) {
            val pcm = ByteArray(CallPlaybackQueue.WRITE_BYTES)
            try {
                while (true) {
                    val block = queue.take(pcm) ?: break
                    try {
                        writerLock.withLock {
                            if (queue.isCurrent(block)) audio.playback.write(pcm, 0, block.byteCount)
                        }
                    } finally {
                        queue.release(block)
                    }
                }
            } catch (_: IOException) {
                // 播放管道关了
            }
        }
        val said = StringBuilder()
        try {
            opened.events.collect { event ->
                when (event) {
                    is VolcRealtimeEvent.Audio -> {
                        if (queue.offer(event.pcm) == null) throw CallMediaFailure("语音积压过多")
                        update { it.copy(phase = VoiceChatPhase.SPEAKING, aiSpeaking = true) }
                    }
                    VolcRealtimeEvent.UserSpeaking, VolcRealtimeEvent.AssistantInterrupted -> {
                        // 你一开口就把没播完的清掉,再刷一次兜住刚好写进管道的那一块
                        queue.invalidate()
                        withTimeoutOrNull(600) { writerLock.withLock { audio.flushPlayback() } }
                            ?: audio.flushPlayback()
                        said.clear()
                        update {
                            it.copy(phase = VoiceChatPhase.LISTENING, aiSpeaking = false, lastAssistantReply = "")
                        }
                    }
                    is VolcRealtimeEvent.UserTranscript -> {
                        update { it.copy(partial = if (event.isFinal) "" else event.text) }
                    }
                    is VolcRealtimeEvent.AssistantTranscript -> {
                        said.append(event.delta)
                        val text = said.toString()
                        update { it.copy(lastAssistantReply = text, aiSpeaking = true) }
                    }
                    VolcRealtimeEvent.AssistantTextEnded -> {
                        val text = said.toString()
                        said.clear()
                        update {
                            it.copy(lastAssistantReply = text, aiSpeaking = false, phase = VoiceChatPhase.LISTENING)
                        }
                    }
                    VolcRealtimeEvent.AssistantAudioEnded -> Unit
                    is VolcRealtimeEvent.Failed -> throw event.error
                    VolcRealtimeEvent.Closed -> throw CallMediaFailure("实时语音会话已结束")
                    else -> Unit
                }
            }
            fail("实时语音连接已断开")
        } finally {
            queue.close()
            writer.cancel()
        }
    }

    /**
     * 静音如意 AI 的朗读:喇叭不出声,麦克风照常听你说话。
     * 不是「静自己的麦」——那样对方说话你也听不见,还把你自己禁言了。
     */
    fun setMuted(muted: Boolean) {
        _muted.value = muted
        audio.setPlaybackMuted(muted)
        update { it.copy(aiSpeaking = if (muted) false else it.aiSpeaking) }
    }

    fun setSpeaker(on: Boolean) {
        _speaker.value = on
        audio.setSpeaker(on)
    }

    /** 说完话收尾:关会话、关音频、停协程。 */
    suspend fun stop(reason: VoiceChatEnd = VoiceChatEnd.USER_HUNG_UP) {
        update { it.copy(phase = VoiceChatPhase.ENDED, endReason = reason, aiSpeaking = false, userSpeaking = false) }
        val pending = jobs
        jobs = emptyList()
        pending.forEach { it.cancel() }
        runCatching { session?.close() }
        session = null
        pending.forEach { runCatching { it.cancelAndJoin() } }
        audio.closeAndJoin()
        scope.cancel()
    }

    private fun fail(detail: String) {
        if (_state.value.phase == VoiceChatPhase.ENDED) return
        update {
            it.copy(
                phase = VoiceChatPhase.ENDED, endReason = VoiceChatEnd.CONNECT_FAILED,
                errorDetail = detail, aiSpeaking = false, userSpeaking = false,
            )
        }
        // 管子断了就别再让它继续收
        runCatching { session?.close() }
        audio.close()
    }

    private fun update(block: (VoiceChatState) -> VoiceChatState) = _state.update(block)

    private fun describe(e: Exception): String = when (e) {
        is VolcRealtimeException -> when (e.kind) {
            VolcRealtimeException.Kind.AUTH -> "鉴权失败,检查 App ID / Access Key"
            VolcRealtimeException.Kind.RATE_LIMITED -> "调用太频繁或额度用完了"
            VolcRealtimeException.Kind.NETWORK -> "网络连不上实时语音"
            VolcRealtimeException.Kind.PROVIDER -> "实时语音服务返回错误${e.code?.let { code -> "($code)" } ?: ""}"
            else -> e.message ?: "实时语音出错"
        }
        is CallMediaFailure -> e.message ?: "实时语音中断"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        /** 它的角色:只要自然聊天,不承诺替用户操作手机。 */
        private const val SYSTEM_PROMPT =
            "你是如意 AI,一个说话自然、简短、口语化的语音助手。回答尽量一两句话,不要列清单,不要用 Markdown。"
    }
}
