package com.mindtravel.reduction.feature.voice

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.hardware.audio.Loudness
import com.mindtravel.reduction.hardware.audio.SpeechOutput
import com.mindtravel.reduction.model.voice.SpeechVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Streaming speech: reply text goes in as it is generated, audio comes out as soon
 * as the first piece is synthesised. Later pieces are synthesised ahead of playback.
 * Audio goes into the shared audio queue, so speech follows a cue instead of talking over it.
 */
class ReplyStreamer(
    private val openVoice: suspend (String) -> SpeechVoice,
    private val player: SpeechOutput,
    private val scope: CoroutineScope
) {
    /** Delivered on the thread of the scope this was given. Exactly one of onDone / onError ends a session that is not stopped. */
    interface Events {
        fun onStart()
        fun onDone()
        fun onError(message: String)
    }

    @Volatile
    private var session = 0
    private var job: Job? = null
    private var texts: Channel<String>? = null
    private var extractor = SpeechTextExtractor()
    private var splitter = SentenceSplitter()

    @Synchronized
    fun start(voice: String, events: Events) {
        stop()
        val current = ++session
        extractor = SpeechTextExtractor()
        splitter = SentenceSplitter()
        val queue = Channel<String>(Channel.UNLIMITED)
        texts = queue

        val fail = { message: String ->
            if (abort(current)) scope.launch { events.onError(message) }
        }
        player.start(
            onStart = { if (current == session) events.onStart() },
            onDone = { if (current == session) events.onDone() }
        )
        job = scope.launch(Dispatchers.IO) {
            try {
                openVoice(voice).use { selected ->
                    var index = 0
                    for (text in queue) {
                        if (current != session) return@launch
                        // Timings go to the log: they show where a pause in speech comes from.
                        val synthStart = System.currentTimeMillis()
                        // Every piece at one loudness: the same as the cues around it.
                        val pcm = Loudness.leveled(selected.synthesize(text))
                        if (pcm.isEmpty()) continue
                        val synthMs = System.currentTimeMillis() - synthStart
                        if (current != session) return@launch
                        val queueStart = System.currentTimeMillis()
                        if (current != session || !player.send(pcm)) return@launch
                        val audioMs = pcm.size / 48L
                        Logs.add(
                            "TTS piece ${index++} chars=${text.length} synth=${synthMs}ms " +
                                "audio=${audioMs}ms waitedForPlayer=${System.currentTimeMillis() - queueStart}ms"
                        )
                    }
                }
                synchronized(this@ReplyStreamer) { if (current == session) player.finish() }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Logs.add("TTS synthesis failed: ${t.message}")
                fail(t.message ?: "TTS 失败")
            }
        }
    }

    /** Feeds raw reply text. Markdown is allowed: what cannot be read aloud is left out. */
    @Synchronized
    fun push(text: String) {
        val queue = texts ?: return
        for (piece in splitter.push(extractor.push(text))) queue.trySend(piece)
    }

    /** No more text is coming; the session ends after the queued audio has played. */
    @Synchronized
    fun finish() {
        val queue = texts ?: return
        val tail = extractor.finish()
        for (piece in splitter.push(tail) + splitter.finish()) queue.trySend(piece)
        queue.close()
        texts = null
    }

    /** Stops at once. No event is delivered for the stopped session. */
    @Synchronized
    fun stop() {
        session++
        job?.cancel()
        job = null
        texts?.close()
        texts = null
        player.stop()
    }

    @Synchronized
    private fun abort(current: Int): Boolean {
        if (current != session) return false
        stop()
        return true
    }
}
