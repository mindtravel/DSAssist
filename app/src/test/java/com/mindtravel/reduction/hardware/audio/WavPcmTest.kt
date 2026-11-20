package com.mindtravel.reduction.hardware.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmTest {

    private fun wav(sampleRate: Int, samples: ShortArray, extraChunk: Boolean = false, format: Int = 1): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val extra = if (extraChunk) 8 + 5 + 1 else 0
        val out = ByteBuffer.allocate(44 + extra + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + extra + data.capacity()).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        if (extraChunk) out.put("LIST".toByteArray()).putInt(5).put(ByteArray(5)).put(0)
        out.put("data".toByteArray()).putInt(data.capacity()).put(data.array())
        return out.array()
    }

    @Test
    fun readsRateAndSamples() {
        val pcm = WavPcm.parse(wav(24_000, shortArrayOf(1, -2, 300)))
        assertEquals(24_000, pcm.sampleRate)
        assertEquals(1, pcm.channels)
        assertEquals(3, pcm.frames)
        assertArrayEquals(byteArrayOf(1, 0, -2, -1, 44, 1), pcm.data)
    }

    @Test
    fun skipsChunksItDoesNotNeedIncludingOddSizedOnes() {
        val pcm = WavPcm.parse(wav(16_000, ShortArray(1600), extraChunk = true))
        assertEquals(1600, pcm.frames)
        assertEquals(100L, pcm.durationMs)
    }

    @Test
    fun refusesWhatItCannotPlay() {
        assertThrows(IllegalArgumentException::class.java) { WavPcm.parse("not audio at all".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { WavPcm.parse(wav(24_000, ShortArray(4), format = 3)) }
    }

    @Test
    fun decodesTheEightBitAndFloatFormatsAndroidSpeechEnginesCanProduce() {
        val bytes = wav(22_050, shortArrayOf(0, 0))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(34, 8)
        bytes[44] = 0
        bytes[45] = 127
        bytes[46] = 128.toByte()
        bytes[47] = 255.toByte()
        assertArrayEquals(byteArrayOf(0, -128, 0, -1, 0, 0, 0, 127), WavPcm.parse(bytes).data)

        // Android's file synthesizer writes the PCM format tag even for a float engine.
        header.putShort(34, 32)
        header.putFloat(44, -0.5f)
        assertArrayEquals(byteArrayOf(1, -64), WavPcm.parse(bytes, androidTts = true).data)
    }

    @Test
    fun theBundledCuesAreValid() {
        val raw = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/res/raw") }
            .plus(File("src/main/res/raw"))
            .first { it.isDirectory }
        val cues = raw.listFiles { file -> file.name.startsWith("ack_") }!!.sortedBy { it.name }
        assertEquals(listOf("ack_done.wav", "ack_hum.wav"), cues.map { it.name })
        for (cue in cues) {
            val pcm = WavPcm.parse(cue.readBytes())
            assertEquals(cue.name, 24_000, pcm.sampleRate)
            // The hum is stereo; what is spoken is mono.
            assertEquals(cue.name, if (cue.name == "ack_hum.wav") 2 else 1, pcm.channels)
        }
    }
}
