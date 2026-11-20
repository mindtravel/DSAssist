package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.feature.voice.Cue
import com.mindtravel.reduction.hardware.audio.Loudness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The cues as sound, ready before they are needed — they are what is heard when the
 * network is down, so they cannot depend on it. Each is synthesised once per voice, in
 * the background, and kept; until then, and whenever that is impossible, the recording
 * bundled with the app is played. All are as loud as the speech around them.
 */
internal class SpokenCues(
    private val settingsRepository: SettingsGateway,
    private val synthesizer: DesktopTts,
    bundled: Map<Cue, ByteArray>,
    private val sampleRate: Int,
    private val scope: CoroutineScope
) {
    private val bundled = bundled.mapValues { Loudness.leveled(it.value) }
    private val spoken = ConcurrentHashMap<String, ByteArray>()
    private val asked = ConcurrentHashMap.newKeySet<String>()

    /** What to play now. Never waits, never fails. */
    fun sound(cue: Cue): ByteArray {
        val voice = settingsRepository.load().voice
        return spoken[key(voice, cue)] ?: bundled.getValue(cue).also { prepare() }
    }

    /** Gets every cue ready in the current voice, if it is not already. */
    fun prepare() {
        val settings = settingsRepository.load()
        val voice = settings.voice
        if (settings.apiKey.isBlank()) return
        for (cue in Cue.entries) {
            val key = key(voice, cue)
            if (spoken.containsKey(key) || !asked.add(key)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    val pcm = Loudness.trimmed(synthesizer.synthesize(settings.apiKey, voice, cue.text), sampleRate)
                    val millis = pcm.size / 2 * 1000L / sampleRate
                    check(millis in MIN_MS..(MS_PER_CHAR * cue.text.length)) { "it came out ${millis}ms long" }
                    spoken[key] = Loudness.leveled(pcm)
                    Logs.add("Cue ${cue.name} ready in voice=$voice ${millis}ms")
                } catch (t: Throwable) {
                    Logs.add("Cue ${cue.name} not synthesised for voice=$voice: ${t.message}")
                    asked.remove(key)
                }
            }
        }
    }

    private fun key(voice: String, cue: Cue) = "$voice/${cue.name}"

    private companion object {
        /** Outside these bounds it is not the words that were asked for. */
        const val MIN_MS = 200L
        const val MS_PER_CHAR = 450L
    }
}
