package com.mindtravel.reduction.feature.voice

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.audio.AudioGateway
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.hardware.audio.HeadsetMicController
import com.mindtravel.reduction.hardware.audio.HeadsetMicController.User.PUSH_TO_TALK
import com.mindtravel.reduction.hardware.audio.WavRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class PushToTalkSession(
    private val settingsRepository: SettingsGateway,
    private val audioGateway: AudioGateway,
    private val wavRecorder: WavRecorder,
    private val headsetMic: HeadsetMicController,
    /** Keeps a recording, with what was heard in it, for later use. */
    private val keepRecording: (kind: String, wav: ByteArray, heard: String) -> Unit,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) {
    private val finishing = AtomicBoolean(false)

    fun start(id: String) {
        if (wavRecorder.isRecording() || finishing.get()) {
            emitter.emit("onError", id, "正在处理上一段语音")
            return
        }

        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onError", id, "请先在设置里填写 API Key")
            return
        }

        val useHeadset = settings.headsetMicEnabled && headsetMic.request(PUSH_TO_TALK) && headsetMic.awaitReady(PUSH_TO_TALK)
        if (!useHeadset) headsetMic.release(PUSH_TO_TALK)
        val started = wavRecorder.start(useCommunicationSource = useHeadset) {
            emitter.emit("onRecordingLimit", id)
            scope.launch { finish(id) }
        }
        if (!started) {
            headsetMic.release(PUSH_TO_TALK)
            emitter.emit("onError", id, "无法启动录音")
            return
        }
        emitter.emit("onRecordingStart", id)
    }

    fun stopAndTranscribe(id: String) {
        scope.launch { finish(id) }
    }

    fun isActive(): Boolean = wavRecorder.isRecording() || finishing.get()

    fun cancel() {
        wavRecorder.cancel()
        headsetMic.release(PUSH_TO_TALK)
        finishing.set(false)
    }

    fun cancelActive() {
        wavRecorder.cancel()
        headsetMic.release(PUSH_TO_TALK)
    }

    fun dispose() = cancel()

    private suspend fun finish(id: String) {
        if (!finishing.compareAndSet(false, true)) return
        try {
            val wav = wavRecorder.stop()
            if (wav == null || wav.size < 1_000) {
                emitter.emit("onAsrResult", id, false, "没有录到声音，请再试一次")
                return
            }
            transcribe(id, wav)
        } catch (t: Throwable) {
            Logs.add("ASR failed: ${t.message}")
            emitter.emit("onAsrResult", id, false, t.message ?: "识别失败")
        } finally {
            headsetMic.release(PUSH_TO_TALK)
            finishing.set(false)
        }
    }

    private suspend fun transcribe(id: String, wav: ByteArray) {
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onAsrResult", id, false, "请先在设置里填写 API Key")
            return
        }
        emitter.emit("onStatus", id, "识别中")
        val raw = withContext(Dispatchers.IO) { audioGateway.transcribe(settings.apiKey, wav) }
        val text = clean(raw)
        withContext(Dispatchers.IO) { keepRecording("talk", wav, text) }
        if (text.isBlank()) {
            emitter.emit("onAsrResult", id, false, "没有识别到内容，请重说")
        } else {
            emitter.emit("onAsrResult", id, true, text)
        }
    }

    private fun clean(raw: String): String {
        val body = EndWords.strip(raw)
        return (body ?: raw).trim()
    }
}
