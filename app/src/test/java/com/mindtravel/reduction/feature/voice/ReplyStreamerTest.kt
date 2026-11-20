package com.mindtravel.reduction.feature.voice

import com.mindtravel.reduction.hardware.audio.SpeechOutput
import com.mindtravel.reduction.model.voice.SpeechVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyStreamerTest {
    @Test fun oneVoiceIsOpenedForAllSentencesAndReleasedAfterSynthesis() = runBlocking {
        val texts = mutableListOf<String>()
        val output = Output()
        var opens = 0
        var closes = 0
        val streamer = ReplyStreamer({
            opens++
            object : SpeechVoice {
                override suspend fun synthesize(text: String): ByteArray {
                    texts += text
                    return byteArrayOf(1, 0)
                }
                override fun close() { closes++ }
            }
        }, output, this)
        streamer.start("test", output)
        streamer.push("第一句。")
        streamer.push("第二句。")
        streamer.finish()
        withTimeout(5_000) { output.done.await() }
        assertEquals(listOf("第一句。", "第二句。"), texts)
        assertEquals(1, opens)
        assertEquals(1, closes)
    }

    @Test fun interruptDuringSynthesisReleasesTheVoiceAndDropsItsAudio() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val output = Output()
        val streamer = ReplyStreamer({
            object : SpeechVoice {
                override suspend fun synthesize(text: String): ByteArray {
                    started.complete(Unit)
                    awaitCancellation()
                }
                override fun close() { closed.complete(Unit) }
            }
        }, output, this)
        streamer.start("test", output)
        streamer.push("开始朗读。")
        withTimeout(5_000) { started.await() }
        streamer.stop()
        withTimeout(5_000) { closed.await() }
        assertEquals(0, output.pieces)
        assertTrue(!output.done.isCompleted)
    }

    private class Output : SpeechOutput, ReplyStreamer.Events {
        val done = CompletableDeferred<Unit>()
        var pieces = 0
        override fun start(onStart: () -> Unit, onDone: () -> Unit) {}
        override suspend fun send(pcm: ByteArray): Boolean { pieces++; return true }
        override fun finish() { done.complete(Unit) }
        override fun stop() {}
        override fun onStart() {}
        override fun onDone() {}
        override fun onError(message: String) { done.completeExceptionally(AssertionError(message)) }
    }
}
