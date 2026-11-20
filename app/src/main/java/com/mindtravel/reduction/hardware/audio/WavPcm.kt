package com.mindtravel.reduction.hardware.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** 16-bit PCM read out of a WAV file. */
class WavPcm(val sampleRate: Int, val channels: Int, val data: ByteArray) {

    val frames: Int get() = data.size / (2 * channels)
    val durationMs: Long get() = frames * 1000L / sampleRate

    companion object {
        /** Android TTS also writes its 32-bit float samples with a linear PCM format tag. */
        fun parse(bytes: ByteArray, androidTts: Boolean = false): WavPcm {
            require(bytes.size >= 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "not a WAV file" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val id = tag(bytes, offset)
                val size = buffer.getInt(offset + 4)
                val body = offset + 8
                require(size >= 0 && (size <= bytes.size - body || id == "data")) { "broken WAV chunk $id" }
                when (id) {
                    "fmt " -> {
                        require(size >= 16) { "incomplete WAV format" }
                        format = buffer.getShort(body).toInt() and 0xffff
                        channels = buffer.getShort(body + 2).toInt()
                        sampleRate = buffer.getInt(body + 4)
                        bits = buffer.getShort(body + 14).toInt()
                    }
                    "data" -> {
                        require(channels in 1..2 && sampleRate > 0) { "invalid WAV channels or rate" }
                        val floatPcm = bits == 32 && (format == 3 || androidTts && format == 1)
                        require(format == 1 && (bits == 8 || bits == 16) || floatPcm) { "unsupported WAV sample format" }
                        val frameSize = channels * (bits / 8)
                        val length = minOf(size, bytes.size - body).let { it - it % frameSize }
                        val data = if (bits == 16) {
                            bytes.copyOfRange(body, body + length)
                        } else {
                            buffer.position(body)
                            val samples = length / (bits / 8)
                            val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
                            repeat(samples) {
                                val value = if (floatPcm) {
                                    (buffer.float.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                                } else {
                                    ((buffer.get().toInt() and 0xff) - 128) shl 8
                                }
                                pcm.putShort(value.toShort())
                            }
                            pcm.array()
                        }
                        return WavPcm(sampleRate, channels, data)
                    }
                }
                // Chunks are padded to an even length.
                offset = body + size + (size and 1)
            }
            throw IllegalArgumentException("WAV file has no audio data")
        }

        private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
    }
}
