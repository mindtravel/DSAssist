package com.mindtravel.reduction.model.voice

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.hardware.audio.WavPcm
import kotlin.math.roundToInt

/** The bundled Mandarin voice used when system synthesis is unavailable. */
class TtsSynthesizer(
    private val cache: TtsCache,
    private val generate: (text: String, speaker: Int) -> GeneratedAudio
) {
    fun openVoice(voice: String) = SpeechVoice { text -> synthesize(voice, text) }

    // A stopped reply may still be finishing native inference when the next one begins.
    @Synchronized
    fun synthesize(voice: String, text: String): ByteArray {
        val speaker = if (voice == "tide" || voice == "aishell3-1") 1 else 0
        val key = "aishell3-$speaker"
        cache.get(key, text)?.let {
            Logs.add("Local TTS cache hit voice=$key")
            return it
        }
        if (text.none { it.isLetterOrDigit() }) return ByteArray(0)
        val audio = generate(spokenText(text), speaker)
        Logs.add("Local TTS generated with $ENGINE_NAME voice=$key")
        val pcm = resample(audio.samples.size, audio.sampleRate) { audio.samples[it] }
        cache.put(key, text, pcm)
        return pcm
    }

    companion object {
        const val MODEL_DIR = "tts-aishell3"
        const val ENGINE_NAME = "sherpa-onnx · AISHELL-3 VITS (CPU)"
        private const val PLAYBACK_RATE = 24_000

        fun rules(dir: String): String =
            listOf("phone", "date", "number").joinToString(",") { "$dir/$it.fst" }

        // The Mandarin lexicon has no Latin letters. Keep abbreviations such as API audible.
        private val letterNames = "诶 比 西 迪 伊 艾弗 吉 艾尺 艾 杰 开 艾勒 艾姆 恩 欧 皮 丘 阿尔 艾丝 提 优 维 达布柳 艾克斯 歪 泽德".split(' ')

        private fun spokenText(text: String) = buildString {
            for (ch in text) {
                val index = ch.lowercaseChar() - 'a'
                if (ch.code < 128 && index in letterNames.indices) append(letterNames[index]) else append(ch)
            }
        }

        internal fun toPlaybackPcm(wav: WavPcm): ByteArray {
            if (wav.sampleRate == PLAYBACK_RATE && wav.channels == 1) return wav.data
            fun sample(at: Int) = (wav.data[at].toInt() and 0xff) or (wav.data[at + 1].toInt() shl 8)
            return resample(wav.frames, wav.sampleRate) { frame ->
                val offset = frame * wav.channels * 2
                val mono = if (wav.channels == 1) sample(offset).toFloat() else (sample(offset) + sample(offset + 2)) / 2f
                mono / Short.MAX_VALUE
            }
        }

        private inline fun resample(count: Int, rate: Int, sample: (Int) -> Float): ByteArray {
            if (count == 0) return ByteArray(0)
            require(rate > 0) { "本地朗读的采样率无效" }
            val frames = ((count.toLong() * PLAYBACK_RATE + rate / 2) / rate).toInt()
            val pcm = ByteArray(frames * 2)
            for (i in 0 until frames) {
                val position = i.toLong() * rate
                val left = (position / PLAYBACK_RATE).toInt()
                val right = minOf(left + 1, count - 1)
                val fraction = (position % PLAYBACK_RATE).toFloat() / PLAYBACK_RATE
                val first = sample(left)
                val value = ((first + (sample(right) - first) * fraction).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                pcm[i * 2] = value.toByte()
                pcm[i * 2 + 1] = (value shr 8).toByte()
            }
            return pcm
        }
    }
}
