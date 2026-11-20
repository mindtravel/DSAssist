package com.mindtravel.reduction.model.voice

import com.k2fsa.sherpa.onnx.GeneratedAudio
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TtsSynthesizerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun localVoicesUseNewCacheKeysAndPlayAt24kHz() {
        val cache = TtsCache(folder.root)
        val text = "测试 API"
        cache.put("tide", text, byteArrayOf(0))
        val speakers = mutableListOf<Int>()
        val synth = TtsSynthesizer(cache) { words, speaker ->
            assertEquals("测试 诶皮艾", words)
            speakers += speaker
            GeneratedAudio(floatArrayOf(0f, 1f), 8_000)
        }
        val audio = synth.synthesize("tide", text)
        val samples = ShortArray(6)
        ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        assertArrayEquals(shortArrayOf(0, 10922, 21845, 32767, 32767, 32767), samples)
        assertArrayEquals(audio, synth.synthesize("aishell3-1", text))
        synth.synthesize("stella", text)
        synth.synthesize("stella", "……")
        assertEquals(listOf(1, 0), speakers)
    }
}
