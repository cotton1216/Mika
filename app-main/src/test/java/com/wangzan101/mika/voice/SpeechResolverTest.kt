package com.wangzan101.mika.voice

import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechResolverTest {
    private val none = SystemCapability(available = false, onDevice = false)
    private val p1 = Provider(id = "p1", name = "P1", kind = ProviderConfig.Kind.OpenAiCompat, baseUrl = "https://example.com/v1")

    @Test fun autoUsesSystemWhenAvailable() {
        assertEquals(
            SpeechEngineSpec.System(onDevice = true),
            SpeechResolver.resolve(SpeechChoice.Auto, SystemCapability(true, true), emptyList()),
        )
    }

    @Test fun autoWithoutSystemIsUnavailable() {
        assertEquals(
            SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.NoSystemNoModel),
            SpeechResolver.resolve(SpeechChoice.Auto, none, emptyList()),
        )
    }

    @Test fun systemChosenButGone() {
        assertEquals(
            SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemGone),
            SpeechResolver.resolve(SpeechChoice.System, none, emptyList()),
        )
    }

    @Test fun silentSystemIsUnavailableNotSwappedForAProvider() {
        val silent = SystemCapability(available = true, onDevice = false, responsive = false)
        for (choice in listOf(SpeechChoice.Auto, SpeechChoice.System)) {
            assertEquals(
                SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse),
                SpeechResolver.resolve(choice, silent, listOf(p1)),
            )
        }
    }

    @Test fun providerGone() {
        val choice = SpeechChoice.Provider("p9", "whisper-1", TranscribeMethod.AudioTranscriptions)
        assertEquals(
            SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderGone),
            SpeechResolver.resolve(choice, SystemCapability(true, false), listOf(p1)),
        )
    }

    @Test fun providerFound() {
        val choice = SpeechChoice.Provider("p1", "whisper-1", TranscribeMethod.AudioTranscriptions)
        assertEquals(
            SpeechEngineSpec.Provider(p1, "whisper-1", TranscribeMethod.AudioTranscriptions),
            SpeechResolver.resolve(choice, none, listOf(p1)),
        )
    }

    @Test fun offIsOff() {
        assertEquals(
            SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off),
            SpeechResolver.resolve(SpeechChoice.Off, SystemCapability(true, true), listOf(p1)),
        )
    }
}
