package com.mindtravel.reduction.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mindtravel.reduction.R
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.article.ArticleBuilder
import com.mindtravel.reduction.feature.chat.data.HistoryRepository
import com.mindtravel.reduction.feature.sync.DataSync
import com.mindtravel.reduction.feature.sync.PhoneSync
import com.mindtravel.reduction.feature.chat.model.BatteryWatch
import com.mindtravel.reduction.feature.chat.model.ChatEngine
import com.mindtravel.reduction.feature.chat.model.ChatSession
import com.mindtravel.reduction.feature.chat.model.tools.AgentLedger
import com.mindtravel.reduction.feature.chat.model.tools.AgentRuns
import com.mindtravel.reduction.feature.chat.model.tools.ChatToolFactory
import com.mindtravel.reduction.feature.chat.ui.AgentWatch
import com.mindtravel.reduction.feature.chat.ui.ChatController
import com.mindtravel.reduction.feature.chat.ui.Conversation
import com.mindtravel.reduction.feature.memo.MemoCard
import com.mindtravel.reduction.feature.memo.alarm.MemoAlarms
import com.mindtravel.reduction.feature.memo.data.MemoStore
import com.mindtravel.reduction.feature.pc.ui.PcController
import com.mindtravel.reduction.feature.settings.data.SettingsRepository
import com.mindtravel.reduction.feature.update.ui.UpdateController
import com.mindtravel.reduction.feature.voice.AckCue
import com.mindtravel.reduction.feature.voice.Cue
import com.mindtravel.reduction.feature.voice.PushToTalkSession
import com.mindtravel.reduction.feature.voice.RecordingController
import com.mindtravel.reduction.feature.voice.ReplyStreamer
import com.mindtravel.reduction.feature.voice.SpeechController
import com.mindtravel.reduction.feature.voice.TurnListener
import com.mindtravel.reduction.feature.voice.TurnRecorder
import com.mindtravel.reduction.feature.voice.VoiceRuntime
import com.mindtravel.reduction.feature.voice.VoiceState
import com.mindtravel.reduction.feature.voice.WakeControl
import com.mindtravel.reduction.feature.voice.wake.WakeWordBus
import com.mindtravel.reduction.feature.voice.wake.WakeWordService
import com.mindtravel.reduction.hardware.audio.AudioQueue
import com.mindtravel.reduction.hardware.audio.AudioRoutes
import com.mindtravel.reduction.hardware.audio.AudioTrackSink
import com.mindtravel.reduction.hardware.audio.HeadsetMicController
import com.mindtravel.reduction.hardware.audio.Loudness
import com.mindtravel.reduction.hardware.audio.MediaButtons
import com.mindtravel.reduction.hardware.audio.PcmPlayer
import com.mindtravel.reduction.hardware.audio.RecordingArchive
import com.mindtravel.reduction.hardware.audio.WavPcm
import com.mindtravel.reduction.hardware.audio.WavRecorder
import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.hardware.power.DeviceBattery
import com.mindtravel.reduction.hardware.pc.SshPcClient
import com.mindtravel.reduction.model.ModelGraph
import com.mindtravel.reduction.model.voice.RollingAsr
import com.mindtravel.reduction.model.voice.AndroidTts
import com.mindtravel.reduction.model.voice.TtsCache
import com.mindtravel.reduction.model.voice.VoiceDetector
import com.mindtravel.reduction.model.wake.KeywordSpotterModel
import com.mindtravel.reduction.ui.overlay.CardOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.LocalDate

/**
 * Everything a conversation needs, living as long as the process does — not as long as
 * a screen does. The wake word service keeps the process alive; this is what answers
 * when the wake word is heard, whether or not any UI exists at that moment.
 *
 * A UI attaches through [ui] to show what is going on and to send the user's taps. It
 * owns nothing here and can come and go.
 */
class AppCore private constructor(context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ui = UiLink(context)

    val settingsRepository = SettingsRepository(context)
    val cardRepository = CardRepository(File(context.filesDir, "cards"))

    /** To-dos, their times and their reminders: the memo plugin, and the only place they are kept. */
    val memoStore = MemoStore(File(context.filesDir, "memos.json"))
    private val alarms = MemoAlarms(context)

    /** One card can float over other apps; see [CardOverlay]. */
    val cardOverlay = CardOverlay(context, cardRepository)
    private val agentRuns = AgentRuns(File(context.filesDir, "agent-runs.json"))
    private val historyRepository = HistoryRepository(File(context.filesDir, "chat_history.json"))

    // ── hardware ────────────────────────────────────────────────────────────
    private val headsetMic = HeadsetMicController(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioQueue = AudioQueue(AudioTrackSink()) { callback -> mainHandler.post(callback) }
    private val pcmPlayer = PcmPlayer(context, audioQueue, scope)
    private val pcGateway: PcGateway = SshPcClient(context)
    private val deviceBattery = DeviceBattery(context)
    private val recordings = RecordingArchive(File(context.filesDir, "recordings"))

    // ── models ──────────────────────────────────────────────────────────────
    private val models = ModelGraph()
    private val ttsCache = TtsCache(File(context.cacheDir, "tts_cache"))
    internal val tts = AndroidTts(context, ttsCache)

    // ── features: voice ─────────────────────────────────────────────────────
    private val cueSounds = mapOf(
        Cue.OK to cueSound(context, R.raw.ack_done),
        Cue.RETRYING to cueSound(context, R.raw.cue_retrying),
        Cue.NO_NETWORK to cueSound(context, R.raw.cue_no_network),
        Cue.FAILED to cueSound(context, R.raw.cue_failed),
        Cue.NOT_HEARD to cueSound(context, R.raw.cue_not_heard)
    ).mapValues { Loudness.leveled(it.value) }
    private val ackCue = AckCue(
        queue = audioQueue,
        sounds = AckCue.Sounds(
            spoken = cueSounds::getValue,
            hum = sound(context, R.raw.ack_hum, channels = 2),
            humStereo = true,
            // 4 dB above the recording: the same 24 dB under full scale instead of 20.
            humGain = 1.585f
        ),
        scope = scope,
        describeOutput = { AudioRoutes.describe(context) }
    )
    val speech = SpeechController(settingsRepository, ReplyStreamer(tts::openVoice, pcmPlayer, scope), ttsCache, ui)
    private val pushToTalk = PushToTalkSession(
        settingsRepository = settingsRepository,
        audioGateway = models.audioGateway,
        wavRecorder = WavRecorder(),
        headsetMic = headsetMic,
        keepRecording = recordings::keep,
        emitter = ui,
        scope = scope
    )
    val recording = RecordingController(ui, pushToTalk, ui)

    // ── features: chat ──────────────────────────────────────────────────────
    private val chatEngine: ChatEngine = ChatSession(models.chatGateway)
    /** Sets cards as articles: for the assistant's tool, and for the page's preview, which asks for the template in use. */
    val articles = ArticleBuilder(
        asset = { path -> context.assets.open(path).bufferedReader().use { it.readText() } },
        assetBytes = { path -> context.assets.open(path).use { it.readBytes() } },
        card = { name -> cardRepository.read(name) }
    )

    private val toolFactory = ChatToolFactory(
            searchGateway = models.searchGateway,
            pcGateway = pcGateway,
            emitter = ui,
            cards = cardRepository,
            memos = memoStore,
            agentLauncherScript = { harnessScript(context, "run-agent.sh") },
            sessionStatusScript = { harnessScript(context, "session-status.py") },
            agentRuns = agentRuns,
            articles = articles,
            renderArticleScript = { harnessScript(context, "render-article.sh") }
        )
    private val chatController = ChatController(
        settingsRepository = settingsRepository,
        chatEngine = chatEngine,
        toolFactory = toolFactory,
        speech = speech,
        say = ackCue::say,
        emitter = ui,
        scope = scope
    )
    private val batteryWatch = BatteryWatch()
    val conversation = Conversation(
        settingsRepository = settingsRepository,
        history = historyRepository,
        cards = cardRepository,
        chat = chatController,
        emitter = ui,
        today = { LocalDate.now().toString() },
        deviceNotices = {
            batteryWatch.notices(deviceBattery.readings().map { BatteryWatch.Device(it.name, it.percent, it.charging) })
        },
        todoNotices = {
            memoStore.askAboutUndated().map { memo ->
                "第 ${memo.id} 条：${memo.text}"
            }
        }
    )

    private val turnListener = TurnListener(
        audioGateway = models.audioGateway,
        recorder = TurnRecorder(
            voice = VoiceDetector.create(context),
            endWordSpotter = KeywordSpotterModel(context, KeywordSpotterModel.Language.CHINESE),
            keepUnheard = { wav, note -> recordings.keep("unheard", wav, note) }
        ),
        headsetMic = headsetMic,
        keepRecording = recordings::keep,
        rollingAsr = RollingAsr(models.audioGateway, scope),
        scope = scope
    )

    val voice = VoiceRuntime(
        settingsRepository = settingsRepository,
        permissions = ui,
        hasHeadsetOutput = { AudioRoutes.hasHeadsetOutput(context) },
        wake = object : WakeControl {
            override fun pause() {
                mainHandler.post { WakeWordService.pause(context) }
            }

            override fun resume() {
                mainHandler.post { WakeWordService.resume(context) }
            }
        },
        cue = ackCue,
        capture = turnListener,
        isPushToTalkActive = pushToTalk::isActive,
        submit = { text -> conversation.submit(text) },
        abortReply = chatController::cancel,
        emitter = ui,
        scope = scope
    ).also { chatController.observer = it }

    private val mediaButtons = MediaButtons(context) { voice.onHeadsetGesture() }

    /** A sub agent's result reaches the conversation by itself, in a quiet moment. */
    private val agentWatch = AgentWatch(
        finished = {
            toolFactory.endedRuns(settingsRepository.load()).map { ended ->
                AgentWatch.Report(
                    "任务 ${ended.number} 结束了",
                    "【任务 ${ended.number} 已结束】下面是它交回的结果，请向用户汇报。\n\n${ended.report}",
                    ended.images
                )
            }
        },
        quiet = { voice.state.phase == VoiceState.IDLE && !chatController.isBusy() && !pushToTalk.isActive() },
        deliver = { report -> conversation.submit(report.text, report.title, report.images) },
        scope = scope
    )

    // ── features: pc ────────────────────────────────────────────────────────
    val pc = PcController(
        settingsRepository = settingsRepository,
        pcGateway = pcGateway,
        recordings = recordings,
        emitter = ui,
        scope = scope,
        updateManifestUrl = UpdateController.MANIFEST_URL
    )

    /** The conversation, cards and to-dos, shared with the computer whenever the phone is connected to it. */
    private val phoneSync = PhoneSync(
        DataSync(cardRepository, memoStore, conversation, hub = false), pcGateway, settingsRepository, scope
    )

    init {
        // The wake word reaches the conversation directly: no screen is involved.
        WakeWordBus.listener = {
            WakeWordBus.consumePending()
            voice.onWakeWord()
        }
        if (WakeWordBus.consumePending()) voice.onWakeWord()
        // The record of sub agent runs is a card the program keeps, rewritten whenever a run changes.
        val writeLedger = { cardRepository.writeProgramCard(AgentLedger.CARD, AgentLedger.render(agentRuns.all()), AgentLedger.PURPOSE) }
        agentRuns.onChange = { writeLedger() }
        writeLedger()
        // The review card is written from the to-dos whenever they change, and the alarms follow them.
        val writeMemos = {
            cardRepository.writeProgramCard(MemoCard.CARD, MemoCard.render(memoStore), MemoCard.PURPOSE)
            mainHandler.post { runCatching { alarms.reschedule(memoStore) } }
        }
        memoStore.onChange = {
            writeMemos()
            ui.emit("onMemosChanged", "")
            phoneSync.changed()
        }
        writeMemos()
        conversation.onChange = { phoneSync.changed() }
        // A change made on the card is the user's own: the to-dos are read again afterwards.
        cardRepository.onChange = { name ->
            cardOverlay.onCardChanged(name)
            ui.emit("onCardsChanged", "")
            phoneSync.changed()
        }
        mediaButtons.start()
        agentWatch.start()
        phoneSync.start()
        WakeWordBus.clipSink = { wav, keyword -> recordings.keep("wake", wav, keyword) }
        Logs.add("Core started")
    }

    /** A script that runs on the computer, as bundled with the app. */
    private fun harnessScript(context: Context, name: String): String =
        context.assets.open("harness/$name").bufferedReader().use { it.readText() }

    /** A bundled cue as PCM. The cues are 24 kHz mono, the audio queue's own format. */
    private fun cueSound(context: Context, resId: Int): ByteArray = sound(context, resId, channels = 1)

    private fun sound(context: Context, resId: Int, channels: Int): ByteArray {
        val pcm = context.resources.openRawResource(resId).use { WavPcm.parse(it.readBytes()) }
        check(pcm.sampleRate == AudioQueue.SAMPLE_RATE && pcm.channels == channels) { "sound $resId is not 24 kHz with $channels channel(s)" }
        return pcm.data
    }

    companion object {
        @Volatile
        private var instance: AppCore? = null

        /** The one core of this process, created on first use. Call from the main thread. */
        fun get(context: Context): AppCore = instance ?: synchronized(this) {
            instance ?: AppCore(context.applicationContext).also { instance = it }
        }
    }
}
