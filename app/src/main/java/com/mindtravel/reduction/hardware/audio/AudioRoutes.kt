package com.mindtravel.reduction.hardware.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** Where sound is going right now. */
object AudioRoutes {
    /** Reports headset attachment, removal, and completion or loss of a communication route on the main thread. */
    @Suppress("DEPRECATION")
    fun watchHeadsets(context: Context, changed: () -> Unit): () -> Unit {
        val manager = context.getSystemService(AudioManager::class.java) ?: return {}
        val devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) = changed()
            override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) = changed()
        }
        manager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        val stopWatchingRoute: () -> Unit
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { changed() }
            manager.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(context), listener)
            stopWatchingRoute = { manager.removeOnCommunicationDeviceChangedListener(listener) }
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = changed()
            }
            context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            stopWatchingRoute = { context.unregisterReceiver(receiver) }
        }
        return {
            manager.unregisterAudioDeviceCallback(devices)
            stopWatchingRoute()
        }
    }


    /** True when a headset or headphones are connected, wired or wireless. */
    fun hasHeadsetOutput(context: Context): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> true
                else -> false
            }
        }
    }

    /** For the log: the output device and the media volume. */
    fun describe(context: Context): String {
        val manager = context.getSystemService(AudioManager::class.java) ?: return "unknown"
        val volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (if (hasHeadsetOutput(context)) "headset" else "speaker") + ", media volume $volume/$max"
    }
}
