package com.mindtravel.reduction.model.voice

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.core.audio.AudioGateway
import com.mindtravel.reduction.model.DeepSeekHttp

class DeepSeekAudioApi(private val http: DeepSeekHttp) : AudioGateway {

    override fun transcribe(apiKey: String, wav: ByteArray): String {
        val response = http.postMultipart(
            path = "/v1/tools/asr",
            apiKey = apiKey,
            fieldName = "file",
            fileName = "audio.wav",
            contentType = "audio/wav",
            bytes = wav
        )
        return response.stringOrNull("text")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ApiException("没有识别到文字")
    }
}
