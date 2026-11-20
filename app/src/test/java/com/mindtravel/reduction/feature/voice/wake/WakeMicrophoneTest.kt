package com.mindtravel.reduction.feature.voice.wake

import com.mindtravel.reduction.feature.voice.wake.WakeMicrophone.Input
import com.mindtravel.reduction.hardware.audio.HeadsetMicController
import com.mindtravel.reduction.hardware.audio.HeadsetMicController.User
import com.mindtravel.reduction.hardware.audio.HeadsetRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeMicrophoneTest {
    private class Route : HeadsetRoute {
        var device: Int? = 7
        var accepts = true
        @Volatile var connected = true
        val selected = mutableListOf<Int>()
        var releases = 0

        override fun availableDevice() = device
        override fun selected(id: Int) = connected && device == id
        override fun select(id: Int): Boolean {
            selected.add(id)
            return accepts
        }
        override fun clear() { releases++ }
    }

    private val route = Route()
    private val headset = HeadsetMicController(route)

    @Test fun wakeAndSuccessiveRecordingsKeepOneConnectionUntilWakeStops() = runBlocking {
        val wake = WakeMicrophone(headset, this) {}
        wake.refresh(true, resume = true)
        assertEquals(Input.HEADSET, wake.input)
        for (user in listOf(User.TURN, User.PUSH_TO_TALK, User.TURN)) {
            assertTrue(headset.request(user))
            assertTrue(headset.awaitReady(user))
            headset.release(user)
            wake.refresh(true, resume = true)
        }
        assertEquals(listOf(7), route.selected)
        assertEquals(0, route.releases)
        wake.close()
        assertEquals(1, route.releases)
    }

    @Test fun disablingWakeDuringATurnLetsTheTurnFinishOnTheHeadset() = runBlocking {
        val wake = WakeMicrophone(headset, this) {}
        wake.refresh(true)
        assertTrue(headset.request(User.TURN))
        wake.refresh(false)
        assertEquals(Input.PHONE, wake.input)
        assertFalse(headset.ready(User.WAKE))
        assertTrue(headset.ready(User.TURN))
        assertEquals(0, route.releases)
        headset.release(User.TURN)
        assertEquals(1, route.releases)
    }

    @Test fun attachmentStartsWakeAndRemovalFallsBackEvenWithATurnHoldingTheOldDevice() = runBlocking {
        val wake = WakeMicrophone(headset, this) {}
        route.device = null
        wake.refresh(true)
        assertEquals(Input.PHONE, wake.input)
        route.device = 7
        wake.refresh(true)
        assertTrue(headset.request(User.TURN))
        route.device = null
        wake.refresh(true)
        assertEquals(Input.PHONE, wake.input)
        assertFalse(headset.ready(User.TURN))
        route.device = 7
        wake.refresh(true)
        headset.release(User.TURN)
        assertEquals(Input.HEADSET, wake.input)
        assertEquals(listOf(7, 7), route.selected)
        assertTrue(headset.ready(User.WAKE))
        wake.close()
    }

    @Test fun aPendingConnectionCannotRestartListeningAfterTheSwitchIsOff() = runBlocking {
        val changes = mutableListOf<Input>()
        val wake = WakeMicrophone(headset, this, changes::add)
        route.connected = false
        wake.refresh(true)
        yield()
        wake.refresh(false)
        route.connected = true
        wake.refresh(false)
        assertEquals(listOf(Input.CONNECTING, Input.PHONE), changes)
        assertFalse(headset.ready(User.WAKE))
    }

    @Test fun aColdConnectionBecomesTheWakeInputAsSoonAsItIsReady() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val wake = WakeMicrophone(headset, this) { if (it == Input.HEADSET) ready.complete(Unit) }
        route.connected = false
        wake.refresh(true)
        assertEquals(Input.CONNECTING, wake.input)
        yield()
        route.connected = true
        wake.refresh(true) // Android reports that the selected communication device is active.
        withTimeout(1_500) { ready.await() }
        assertEquals(Input.HEADSET, wake.input)
        wake.close()
    }

    @Test fun aRejectedRouteUsesThePhoneAndCanRetryAfterPermissionIsGranted() = runBlocking {
        val wake = WakeMicrophone(headset, this) {}
        route.accepts = false
        wake.refresh(true)
        assertEquals(Input.PHONE, wake.input)
        route.accepts = true
        wake.refresh(true, resume = true)
        assertEquals(Input.HEADSET, wake.input)
        wake.close()
    }

    @Test fun aLateHeadsetRouteCanTakeOverAfterWakeFallsBackToThePhone() = runBlocking {
        val fallback = CompletableDeferred<Unit>()
        val wake = WakeMicrophone(headset, this) { if (it == Input.PHONE) fallback.complete(Unit) }
        route.connected = false
        wake.refresh(true)
        withTimeout(5_000) { fallback.await() }
        route.connected = true
        wake.refresh(true)
        assertEquals(Input.HEADSET, wake.input)
        assertEquals(listOf(7), route.selected)
        wake.close()
    }
}
