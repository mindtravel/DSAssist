package com.mindtravel.reduction.feature.voice.wake

import android.media.MediaRecorder
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.hardware.audio.PcmCapture
import com.mindtravel.reduction.hardware.audio.WavEncoder
import com.mindtravel.reduction.model.wake.KeywordSpotterModel

/**
 * Feeds the microphone into the keyword spotter while the assistant is idle.
 *
 * The wake phrase is "Hi Reduction". Said in one breath, the model reports the phrase.
 * Said with a pause, the model reports only the name, and [PausedPhrase] decides whether
 * a short word and a pause came just before it. The name inside a sentence wakes nothing.
 */
class WakeWordDetector(
    private val model: KeywordSpotterModel,
    private val onWake: () -> Unit,
    /** The audio that led to a detection, so detections can be checked against what was really said. */
    private val onClip: (wav: ByteArray, keyword: String) -> Unit,
    private val onListening: () -> Unit,
    /** The microphone stopped, or started again, delivering real audio. */
    private val onDeaf: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val deafWatch = DeafWatch()
    private val pausedPhrase = PausedPhrase()

    private val capture = PcmCapture()

    /** The last few seconds heard, oldest first. Touched only on the capture thread. */
    private val recent = ArrayDeque<ShortArray>()

    @Volatile
    private var detected = false
    private var headsetInput = false

    fun start(useHeadsetMic: Boolean) {
        if (capture.isRunning()) {
            if (headsetInput == useHeadsetMic) return
            stop()
        }
        headsetInput = useHeadsetMic
        detected = false
        recent.clear()
        try {
            model.open()
        } catch (t: Throwable) {
            Logs.add("Wake word init error: ${t.message}")
            onError(t.message ?: "唤醒词初始化失败")
            return
        }
        onListening()
        deafWatch.reset()
        pausedPhrase.reset()
        Logs.add("Wake word listening started headsetMic=$useHeadsetMic")

        val started = capture.start(
            source = if (useHeadsetMic) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
            onFrame = { frame ->
                if (deafWatch.onFrame(frame)) {
                    Logs.add(if (deafWatch.deaf) "Wake word: microphone is delivering silence" else "Wake word: microphone is back")
                    onDeaf(deafWatch.deaf)
                }
                if (!detected) {
                    recent.addLast(frame.copyOf())
                    if (recent.size > CLIP_FRAMES) recent.removeFirst()
                    pausedPhrase.onFrame(frame)
                    val keyword = model.accept(frame, PcmCapture.SAMPLE_RATE)
                    val paused = keyword == NAME && pausedPhrase.matches()
                    if (keyword != null && (keyword != NAME || paused)) {
                        detected = true
                        Logs.add("Wake word detected: $keyword" + if (paused) " after a pause" else "")
                        capture.requestStop()
                        onWake()
                        runCatching { onClip(clip(), if (paused) "$keyword after a pause" else keyword) }
                        recent.clear()
                    } else if (keyword != null) {
                        // Kept with its audio: these are what the pause rule is tuned on.
                        Logs.add("Wake word: name heard without the phrase, ignored")
                        runCatching { onClip(clip(), "$keyword ignored") }
                    }
                }
            },
            onError = { message ->
                Logs.add("Wake word loop error: $message")
                if (!detected) onError(message)
            }
        )
        if (!started) onError("无法启动唤醒词麦克风")
    }

    private fun clip(): ByteArray {
        val pcm = ByteArray(recent.sumOf { it.size } * 2)
        var at = 0
        for (frame in recent) {
            for (sample in frame) {
                pcm[at++] = (sample.toInt() and 0xff).toByte()
                pcm[at++] = ((sample.toInt() shr 8) and 0xff).toByte()
            }
        }
        return WavEncoder.pcm16ToWav(pcm)
    }

    fun stop() {
        capture.stop()
        model.close()
        Logs.add("Wake word listening stopped")
    }

    fun release() {
        stop()
        model.release()
    }

    private companion object {
        /** Frames are 100 ms: five seconds, enough for the phrase with a pause and what came before it. */
        const val CLIP_FRAMES = 50

        /** What the keywords file calls the name said on its own. */
        const val NAME = "REDUCTION"
    }
}
