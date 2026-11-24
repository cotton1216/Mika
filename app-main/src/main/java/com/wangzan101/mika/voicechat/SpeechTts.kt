package com.wangzan101.mika.voicechat

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.wangzan101.mika.MikaApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 系统自带的语音朗读(Android TextToSpeech):离线、不依赖网络,只是把助手答的话读出来。
 * 只做"读一句话、读完了告诉我、能被打断"三件事。
 */
class SpeechTts(private val app: MikaApp) {

    private var tts: TextToSpeech? = null
    private var ready = false

    private val _finished = MutableStateFlow(true)
    /** 每读完一句(或被停掉)变成 true 一次;等朗读结束时用。 */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    /** 最近一次朗读没读出来的原因;读成功了就清空。 */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val stopped = AtomicBoolean(false)

    /** 初始化;返回 false 表示这台手机没有可用的朗读。 */
    suspend fun init(): Boolean {
        if (tts != null) return ready
        // 系统没设默认引擎时 engine=null 会直接建失败,所以先把 null(走默认)再逐个试装了的引擎
        val candidates = mutableListOf<String?>(null)
        candidates += engines()
        for (enginePkg in candidates) {
            val engine = createEngine(enginePkg) ?: continue
            val lang = engine.setLanguage(Locale.getDefault())
            val ok = if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.SIMPLIFIED_CHINESE).let {
                    it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                }
            } else {
                true
            }
            if (!ok) {
                engine.shutdown()
                continue
            }
            tts = engine
            engine.setOnUtteranceProgressListener(listener)
            ready = true
            return true
        }
        return false
    }

    /** 装了的朗读引擎的包名。 */
    private fun engines(): List<String> {
        val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return app.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }

    private suspend fun createEngine(pkg: String?): TextToSpeech? = withTimeoutOrNull(INIT_TIMEOUT_MS) {
        suspendCancellableCoroutine<TextToSpeech?> { cont ->
            lateinit var created: TextToSpeech
            created = TextToSpeech(app, { status ->
                if (cont.isActive) cont.resume(created.takeIf { status == TextToSpeech.SUCCESS })
            }, pkg)
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            if (!stopped.get()) _finished.value = true
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            _finished.value = true
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            _failure.value = "朗读引擎出错($errorCode)"
            _finished.value = true
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            _finished.value = true
        }
    }

    /** 读一句话;调用前先把 [finished] 置回 false。 */
    fun speak(text: String) {
        val engine = tts ?: return
        stopped.set(false)
        _failure.value = null
        _finished.value = false
        val id = UUID.randomUUID().toString()
        val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (queued != TextToSpeech.SUCCESS) {
            _failure.value = "没能开始朗读"
            _finished.value = true
        }
    }

    /** 停下来(你插话时用)。 */
    fun stop() {
        stopped.set(true)
        tts?.stop()
        _finished.value = true
    }

    fun release() {
        stopped.set(true)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        _finished.value = true
    }
}

private const val INIT_TIMEOUT_MS = 5_000L
