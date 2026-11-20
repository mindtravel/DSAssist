package com.mindtravel.reduction.ui.di

import android.webkit.WebView
import com.mindtravel.reduction.app.AppCore
import com.mindtravel.reduction.feature.memo.alarm.MemoAlarms
import com.mindtravel.reduction.feature.update.ui.UpdateController
import com.mindtravel.reduction.hardware.update.AssetUpdater
import com.mindtravel.reduction.hardware.update.UpdateClient
import com.mindtravel.reduction.ui.activity.MainActivity
import com.mindtravel.reduction.ui.bridge.AndroidBridge
import com.mindtravel.reduction.ui.bridge.CardBridge
import com.mindtravel.reduction.ui.bridge.ChatBridge
import com.mindtravel.reduction.ui.bridge.JsEmitter
import com.mindtravel.reduction.ui.bridge.PcBridge
import com.mindtravel.reduction.ui.bridge.SettingsBridge
import com.mindtravel.reduction.ui.bridge.SystemBridge
import com.mindtravel.reduction.ui.bridge.UpdateBridge
import com.mindtravel.reduction.ui.bridge.VoiceBridge
import kotlinx.coroutines.CoroutineScope

/**
 * The screen's own wiring: the bridge the web page talks to, and the things only a
 * screen can do — install an update, ask for a permission. The conversation itself lives
 * in [AppCore]; this attaches to it and detaches again when the screen goes away.
 */
class AppGraph(
    activity: MainActivity,
    webView: WebView,
    scope: CoroutineScope
) {
    val core = AppCore.get(activity)
    private val emitter = JsEmitter(webView)

    private val updateClient = UpdateClient()
    private val updateController = UpdateController(
        host = activity,
        client = updateClient,
        assetUpdater = AssetUpdater(activity, updateClient, emitter),
        emitter = emitter,
        scope = scope
    )

    private val voiceBridge = VoiceBridge(core.recording, core.speech, core.voice)

    val bridge = AndroidBridge(
        settings = SettingsBridge(core.settingsRepository, core.conversation),
        voice = voiceBridge,
        chat = ChatBridge(core.conversation),
        cards = CardBridge(core.cardRepository, core.cardOverlay, { activity.askToAllowOverlay() }, core.articles),
        pc = PcBridge(core.pc),
        update = UpdateBridge(updateController),
        system = SystemBridge(activity, core.settingsRepository, voiceBridge) { core.tts.description },
        memos = core.memoStore,
        alarms = MemoAlarms(activity)
    )

    init {
        core.ui.attach(events = emitter, permissions = activity, approvals = activity)
        core.pc.onUpdateSignal = { updateController.check() }
    }

    fun onStartup() {
        core.pc.connect()
    }

    fun onResume() {
        core.pc.connect()
    }

    /** The screen is going away; the conversation carries on without it. */
    fun dispose() {
        core.pc.onUpdateSignal = {}
        core.ui.detach(emitter)
    }
}
