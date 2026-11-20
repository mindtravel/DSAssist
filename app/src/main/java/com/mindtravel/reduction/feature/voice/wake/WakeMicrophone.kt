package com.mindtravel.reduction.feature.voice.wake

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.hardware.audio.HeadsetMicController
import com.mindtravel.reduction.hardware.audio.HeadsetMicController.User.WAKE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The wake service owns this route for its lifetime, including pauses for recording and replies. */
internal class WakeMicrophone(
    private val headset: HeadsetMicController,
    private val scope: CoroutineScope,
    private val onInput: (Input) -> Unit
) {
    enum class Input { PHONE, CONNECTING, HEADSET }

    var input = Input.PHONE
        private set
    private var target: Int? = null
    private var connecting: Job? = null

    /** Device callbacks and service commands enter on the main thread. */
    fun refresh(preferHeadset: Boolean, resume: Boolean = false) {
        val device = if (preferHeadset) headset.refreshDevice() else null
        if (device != target || (resume && device != null && connecting == null && !headset.ready(WAKE))) {
            connecting?.cancel()
            connecting = null
            headset.release(WAKE)
            target = device
            if (device != null && headset.request(WAKE, device) && !headset.ready(WAKE)) {
                connecting = scope.launch {
                    delay(HeadsetMicController.LINK_LIMIT_MS)
                    connecting = null
                    val ready = headset.ready(WAKE)
                    if (!ready) {
                        // Keep the request: the system can finish routing after this timeout.
                        Logs.add("Wake headset mic pending, listening on the phone until the route is ready")
                    }
                    publish(if (ready) Input.HEADSET else Input.PHONE)
                }
            }
        }
        val next = when {
            device != null && headset.ready(WAKE) -> {
                connecting?.cancel()
                connecting = null
                Input.HEADSET
            }
            connecting != null -> Input.CONNECTING
            else -> Input.PHONE
        }
        publish(next, resume)
    }

    fun close() {
        connecting?.cancel()
        connecting = null
        target = null
        headset.release(WAKE)
    }

    private fun publish(next: Input, resume: Boolean = false) {
        if (next == input && !resume) return
        input = next
        onInput(next)
    }
}
