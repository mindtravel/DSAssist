package com.mindtravel.reduction.model.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.hardware.audio.WavPcm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

internal fun isInstalledChineseVoice(locale: Locale, requiresNetwork: Boolean, features: Set<String>?): Boolean =
    (locale.language == "zh" || locale.language == "cmn") &&
        !requiresNetwork && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty()

class AndroidTts(private val context: Context, private val cache: TtsCache) {
    private val bundledEngine by lazy {
        val dir = TtsSynthesizer.MODEL_DIR
        val vits = OfflineTtsVitsModelConfig(model = "$dir/model.onnx", lexicon = "$dir/lexicon.txt", tokens = "$dir/tokens.txt")
        OfflineTts(context.assets, OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = vits, numThreads = 2, provider = "cpu"),
            ruleFsts = TtsSynthesizer.rules(dir)
        ))
    }
    private val bundled = TtsSynthesizer(cache) { text, speaker -> bundledEngine.generate(text, speaker) }

    @Volatile var description = "优先使用手机系统的中文离线音色"
        private set

    /** Reopening once per reply picks up changes to the phone's default engine and installed voices. */
    suspend fun openVoice(bundledVoice: String): SpeechVoice {
        val session = Session(bundled.openVoice(bundledVoice))
        try {
            session.connect()
        } catch (e: CancellationException) {
            session.close()
            throw e
        } catch (e: Exception) {
            session.useBundled(e.message ?: "系统朗读不可用")
        }
        return session
    }

    private inner class Session(private val fallback: SpeechVoice) : SpeechVoice {
        private var engine: TextToSpeech? = null
        private var cacheKey = ""

        suspend fun connect() {
            val ready = CompletableDeferred<Int>()
            val tts = TextToSpeech(context) { ready.complete(it) }
            engine = tts
            if (withTimeoutOrNull(5_000) { ready.await() } != TextToSpeech.SUCCESS) {
                return useBundled("系统语音引擎未就绪")
            }
            val voice = selectOfflineChineseVoice(tts) ?: return useBundled("系统没有可用的中文离线音色")
            val engineName = tts.defaultEngine
            cacheKey = "android-tts/$engineName/${voice.name}"
            description = "系统离线 · $engineName · ${voice.name}"
            Logs.add("TTS: $description")
        }

        override suspend fun synthesize(text: String): ByteArray {
            if (text.none { it.isLetterOrDigit() }) return ByteArray(0)
            val tts = engine ?: return fallback.synthesize(text)
            cache.get(cacheKey, text)?.let { return it }
            val pcm = try {
                render(tts, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                useBundled(e.message ?: "系统朗读失败")
                return fallback.synthesize(text)
            }
            cache.put(cacheKey, text, pcm)
            return pcm
        }

        fun useBundled(reason: String) {
            close()
            description = "内置离线普通话（$reason）"
            Logs.add("TTS: $description")
        }

        override fun close() {
            val tts = engine ?: return
            engine = null
            tts.shutdown()
        }
    }

    private fun selectOfflineChineseVoice(tts: TextToSpeech): Voice? {
        val preferred = tts.defaultVoice?.name
        return tts.voices.orEmpty()
            .filter { isInstalledChineseVoice(it.locale, it.isNetworkConnectionRequired, it.features) }
            .sortedWith(compareByDescending<Voice> { it.name == preferred }
                .thenByDescending { it.locale.country == "CN" }.thenByDescending { it.quality }.thenBy { it.name })
            .firstOrNull { tts.setVoice(it) == TextToSpeech.SUCCESS }
    }

    /** The system produces a WAV; playback stays on our queue so speech follows cues and can be interrupted. */
    private suspend fun render(tts: TextToSpeech, text: String): ByteArray {
        val file = File.createTempFile("system-tts-", ".wav", context.cacheDir)
        try {
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == file.name) done.complete(true) }
                @Deprecated("Required by UtteranceProgressListener")
                override fun onError(utteranceId: String?) { if (utteranceId == file.name) done.complete(false) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == file.name) done.complete(false)
                }
            })
            check(tts.synthesizeToFile(text, Bundle(), file, file.name) == TextToSpeech.SUCCESS &&
                withTimeoutOrNull(30_000) { done.await() } == true) { "系统语音合成失败" }
            return TtsSynthesizer.toPlaybackPcm(WavPcm.parse(file.readBytes(), androidTts = true))
        } finally {
            file.delete()
        }
    }
}
