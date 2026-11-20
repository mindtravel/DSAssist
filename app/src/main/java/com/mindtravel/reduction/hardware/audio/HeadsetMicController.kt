package com.mindtravel.reduction.hardware.audio

import android.content.Context
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.mindtravel.reduction.core.Logs

/** One system communication route, shared by wake listening, hands-free turns and push-to-talk. */
class HeadsetMicController internal constructor(private val route: HeadsetRoute) {
    constructor(context: Context) : this(AndroidHeadsetRoute(context))

    enum class User { WAKE, TURN, PUSH_TO_TALK }

    private val users = mutableSetOf<User>()
    private var device: Int? = null

    /** Forget a removed endpoint even if a recording still holds its share of the old route. */
    @Synchronized
    fun refreshDevice(): Int? {
        val available = route.availableDevice()
        if (device != null && device != available) {
            route.clear()
            device = null
        }
        return available
    }

    @Synchronized
    fun request(user: User, target: Int? = refreshDevice()): Boolean {
        if (target == null) {
            release(user)
            return false
        }
        if (device != target) {
            if (device != null) route.clear()
            device = null
            if (!route.select(target)) return false
            device = target
        }
        users.add(user)
        return true
    }

    @Synchronized
    fun ready(user: User): Boolean = user in users && device?.let(route::selected) == true

    /** Called on the recording worker when opening a cold route. A held, ready route returns immediately. */
    fun awaitReady(user: User): Boolean {
        if (ready(user)) return true
        val start = System.nanoTime()
        while (synchronized(this) { user in users } && (System.nanoTime() - start) / 1_000_000 < LINK_LIMIT_MS) {
            Thread.sleep(50)
            if (ready(user)) {
                Logs.add("Headset mic ready for $user after ${(System.nanoTime() - start) / 1_000_000}ms")
                return true
            }
        }
        return false
    }

    @Synchronized
    fun release(user: User) {
        if (users.remove(user) && users.isEmpty() && device != null) {
            route.clear()
            device = null
        }
    }

    internal companion object {
        const val LINK_LIMIT_MS = 2_500L
    }
}

internal interface HeadsetRoute {
    fun availableDevice(): Int?
    fun select(id: Int): Boolean
    fun selected(id: Int): Boolean
    fun clear()
}

private class AndroidHeadsetRoute(private val context: Context) : HeadsetRoute {
    private val manager = context.getSystemService(AudioManager::class.java)

    override fun availableDevice(): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val headsets = manager.availableCommunicationDevices.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            val current = manager.communicationDevice?.id
            (headsets.firstOrNull { it.id == current } ?: headsets.firstOrNull())?.id
        } else {
            val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) + manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            // Legacy SCO selects the Bluetooth headset implicitly. Its media and call ports have different IDs.
            if (devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) 0 else null
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    override fun select(id: Int): Boolean = try {
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.availableCommunicationDevices.firstOrNull { it.id == id }?.let(manager::setCommunicationDevice) == true
        } else {
            manager.startBluetoothSco()
            manager.isBluetoothScoOn = true
            true
        }
        if (!accepted) clear()
        accepted
    } catch (e: RuntimeException) {
        Logs.add("Headset mic route failed: ${e.message}")
        clear()
        false
    }

    @Suppress("DEPRECATION")
    override fun selected(id: Int): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.communicationDevice?.id == id
        } else {
            val state = context.registerReceiver(null, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            state?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
        }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun clear() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.clearCommunicationDevice()
            } else {
                manager.stopBluetoothSco()
                manager.isBluetoothScoOn = false
            }
        } catch (e: RuntimeException) {
            Logs.add("Headset mic route release failed: ${e.message}")
        } finally {
            runCatching { manager.mode = AudioManager.MODE_NORMAL }
        }
    }
}
