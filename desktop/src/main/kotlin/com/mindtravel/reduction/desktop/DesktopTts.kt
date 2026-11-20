package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.model.DeepSeekHttp
import com.mindtravel.reduction.model.voice.SpeechVoice
import com.mindtravel.reduction.model.voice.TtsCache
import org.json.JSONObject

internal class DesktopTts(private val http: DeepSeekHttp, private val cache: TtsCache) {
    fun openVoice(apiKey: String, voice: String): SpeechVoice {
        require(apiKey.isNotBlank()) { "请先在设置里填写 API Key" }
        return SpeechVoice { text -> synthesize(apiKey, voice, text) }
    }

    fun synthesize(apiKey: String, voice: String, text: String): ByteArray {
        val cached = cache.get(voice, text)
        if (cached != null) {
            Logs.add("TTS cache hit chars=${text.length} bytes=${cached.size}")
            return cached
        }

        val pcm = request(apiKey, text, voice)
        cache.put(voice, text, pcm)
        Logs.add("TTS cache miss chars=${text.length} bytes=${pcm.size}")
        return pcm
    }

    private fun request(apiKey: String, text: String, voice: String): ByteArray {
        if (text.isBlank()) throw ApiException("没有可朗读的文本")
        val body = JSONObject().apply {
            put("text", text)
            put("voice_id", if (voice == "tide") "tide" else "stella")
            put("format", "pcm")
            put("filter_markdown", true)
        }
        Logs.add("TTS request chars=${text.length} voice=$voice")
        val bytes = http.postBytes("/v1/tools/tts", apiKey, body)
        Logs.add("TTS response bytes=${bytes.size}")
        return bytes
    }
}
