package com.mindtravel.reduction.core.audio

interface AudioGateway {
    fun transcribe(apiKey: String, wav: ByteArray): String
}
