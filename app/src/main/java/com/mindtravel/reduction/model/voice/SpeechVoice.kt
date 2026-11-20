package com.mindtravel.reduction.model.voice

/** One voice for a whole reply. Its PCM goes into the same queue as the recording cues. */
fun interface SpeechVoice : AutoCloseable {
    suspend fun synthesize(text: String): ByteArray
    override fun close() {}
}
