package com.mindtravel.reduction.model.voice

import android.speech.tts.TextToSpeech
import com.mindtravel.reduction.hardware.audio.WavPcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class AndroidTtsTest {
    @Test fun onlyInstalledOfflineChineseVoicesAreEligible() {
        assertTrue(isInstalledChineseVoice(Locale.CHINA, false, emptySet()))
        assertTrue(isInstalledChineseVoice(Locale.forLanguageTag("cmn-TW"), false, null))
        assertFalse(isInstalledChineseVoice(Locale.ENGLISH, false, emptySet()))
        assertFalse(isInstalledChineseVoice(Locale.CHINA, true, emptySet()))
        assertFalse(isInstalledChineseVoice(Locale.CHINA, false, setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))
    }

    @Test fun systemAudioIsMixedToMonoAndResampledForTheCueQueue() {
        val stereo = pcm(0, 1000, 2000, 4000)
        assertArrayEquals(pcm(500, 1750, 3000, 3000), TtsSynthesizer.toPlaybackPcm(WavPcm(12_000, 2, stereo)))
        val mono = pcm(-32768, 32767)
        assertSame(mono, TtsSynthesizer.toPlaybackPcm(WavPcm(24_000, 1, mono)))
    }

    private fun pcm(vararg samples: Int): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it.toShort()) }
        }.array()
}
