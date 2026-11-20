package com.mindtravel.reduction.ui.activity

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.mindtravel.reduction.BuildConfig
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.host.PermissionHost
import com.mindtravel.reduction.core.host.ToolApprovalHost
import com.mindtravel.reduction.core.host.UpdateHost
import com.mindtravel.reduction.hardware.update.UpdateInstaller
import com.mindtravel.reduction.hardware.web.HotAssetPathHandler
import com.mindtravel.reduction.ui.di.AppGraph
import com.mindtravel.reduction.feature.settings.data.SettingsRepository
import com.mindtravel.reduction.ui.bridge.AndroidBridge
import com.mindtravel.reduction.feature.voice.wake.WakeWordBus
import com.mindtravel.reduction.feature.voice.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), PermissionHost, UpdateHost, ToolApprovalHost {

    private lateinit var webView: WebView
    private lateinit var graph: AppGraph
    private lateinit var bridge: AndroidBridge
    private lateinit var settingsRepository: SettingsRepository
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var micCallback: ((Boolean) -> Unit)? = null
    private var bluetoothCallback: ((Boolean) -> Unit)? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micCallback?.invoke(granted)
            micCallback = null
        }

    private var filePicked: ValueCallback<Array<Uri>>? = null

    /** Where a photograph taken for the conversation is put before the page reads it. */
    private val shot: java.io.File get() = java.io.File(cacheDir, "shots/shot.jpg")

    /** A picture for the conversation: taken now, or picked from what the phone holds. */
    private val picturePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val picked = result.data?.data
        val uri = when {
            result.resultCode != RESULT_OK -> null
            picked != null -> picked
            shot.length() > 0 -> androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", shot)
            else -> null
        }
        filePicked?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        filePicked = null
    }

    private fun askForPicture(): Boolean = runCatching {
        shot.parentFile?.mkdirs()
        shot.delete()
        val target = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", shot)
        val camera = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, target)
            .addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        camera.clipData = android.content.ClipData.newRawUri("", target)
        val gallery = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("image/*")
        picturePicker.launch(
            android.content.Intent.createChooser(gallery, "图片").putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        )
    }.isSuccess
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filePicked?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        filePicked = null
    }

    private fun mediaType(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        "flac" -> "audio/flac"
        else -> "audio/mp4"
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            bluetoothCallback?.invoke(granted)
            bluetoothCallback = null
            if (granted && settingsRepository.load().wakeWordEnabled) WakeWordService.start(this)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The system's launch screen leaves at once, without a fade of its own: the page's
        // opening animation is the first thing that moves.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { it.remove() }
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#000000"))
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportZoom(false)
                textZoom = 100
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
        }
        setContentView(webView)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        graph = AppGraph(this, webView, uiScope)
        settingsRepository = graph.core.settingsRepository
        bridge = graph.bridge
        webView.addJavascriptInterface(bridge, "Android")
        graph.onStartup()

        if (settingsRepository.load().wakeWordEnabled) {
            setWakeWordEnabled(true)
        }
        if (savedInstanceState == null) handleHeadsetGesture(intent)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                HotAssetPathHandler(
                    this,
                    WebViewAssetLoader.AssetsPathHandler(this)
                )
            )
            // Pictures and sound kept with the cards, by their id.
            .addPathHandler("/card-media/") { path ->
                graph.core.cardRepository.heldFile(path.trim('/'))?.let { file ->
                    WebResourceResponse(mediaType(file.extension), null, file.inputStream())
                }
            }
            .build()
        // Lets the page ask for a picture or a recording to put into a card.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                filePicked?.onReceiveValue(null)
                filePicked = callback
                val kinds = params.acceptTypes.filter { it.isNotBlank() }
                // Asked for pictures only, as the conversation does: the camera is offered beside the gallery.
                if (kinds == listOf("image/*")) return askForPicture()
                return runCatching { filePicker.launch(kinds.singleOrNull() ?: "*/*") }.isSuccess
            }
        }
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            /**
             * The system may take the page's render process away, most often while the app
             * sits in the background. Left unhandled that kills the whole app, wake word
             * and all. The conversation does not live in the page, so only the screen is
             * given up: rebuilt if it is showing, closed if it is not.
             */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val now = SystemClock.elapsedRealtime()
                val again = now - lastRenderLossAt < RENDER_LOSS_LOOP_MS
                lastRenderLossAt = now
                Logs.add("Page render process gone (crashed=${detail.didCrash()}, again=$again); the core carries on")
                // Losing it twice in quick succession means rebuilding will not help: stop trying.
                if (!again && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) recreate() else finish()
                return true
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                if (url.scheme == "http" || url.scheme == "https") {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    }
                    return true
                }
                return true
            }
        }
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun requestMicPermission(callback: (Boolean) -> Unit) {
        runOnUiThread {
            if (hasMicPermission()) {
                callback(true)
                return@runOnUiThread
            }
            micCallback = callback
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun requestBluetoothPermission(callback: (Boolean) -> Unit) {
        runOnUiThread {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                callback(true)
                return@runOnUiThread
            }
            bluetoothCallback = callback
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    override fun cacheDirectory(): File = cacheDir

    override fun installedApk(): File = File(
        packageManager.getApplicationInfo(packageName, 0).sourceDir
    )

    override fun installApk(file: File) {
        UpdateInstaller.installApk(this, file)
    }

    override fun showUpdateReady(version: String, apk: File) {
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Reduction $version")
                .setMessage("新版本已下载，是否现在安装？")
                .setPositiveButton("安装") { _, _ ->
                    runCatching { UpdateInstaller.installApk(this, apk) }
                        .onFailure {
                            android.widget.Toast.makeText(
                                this,
                                it.message ?: "无法打开安装窗口",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    override fun showAssetsReady(version: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("界面资源已更新")
                .setMessage("资源版本 $version 已安装，是否立即重新加载界面？")
                .setPositiveButton("立即重载") { _, _ ->
                    webView.reload()
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    override fun requestToolApproval(title: String, message: String, callback: (Boolean) -> Unit) {
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                callback(false)
                return@runOnUiThread
            }
            val answered = AtomicBoolean(false)
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message.take(2_000))
                .setPositiveButton("允许本次") { _, _ ->
                    if (answered.compareAndSet(false, true)) callback(true)
                }
                .setNegativeButton("拒绝") { _, _ ->
                    if (answered.compareAndSet(false, true)) callback(false)
                }
                .setOnCancelListener {
                    if (answered.compareAndSet(false, true)) callback(false)
                }
                .create()
            dialog.setOnDismissListener {
                if (answered.compareAndSet(false, true)) callback(false)
            }
            dialog.show()
        }
    }

    /** [byUser] is true when the user has just switched it on, the moment to ask for what it needs. */
    fun setWakeWordEnabled(enabled: Boolean, byUser: Boolean = false) {
        if (enabled) {
            if (byUser) askToRunUnrestricted()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            WakeWordBus.reset()
            WakeWordService.start(this)
        } else {
            WakeWordService.stop(this)
            WakeWordBus.reset()
        }
    }

    /**
     * Always-on listening only survives a locked screen if the system does not put the app
     * to sleep. This opens the system's own dialog for that, once it is needed.
     */
    @SuppressLint("BatteryLife")
    private fun askToRunUnrestricted() {
        val power = getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }.onFailure { Logs.add("Battery exemption dialog unavailable: ${it.message}") }
    }

    /** Opens the system page where this app is allowed to set alarms to the minute. */
    fun askForExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }.onFailure { Logs.add("Exact alarm settings unavailable: ${it.message}") }
    }

    /** Asks for the notification permission, which reminders need. */
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Opens the system page where this app is allowed to show a card over other apps. */
    fun askToAllowOverlay() {
        runOnUiThread {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }.onFailure { Logs.add("Overlay settings unavailable: ${it.message}") }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHeadsetGesture(intent)
    }

    /**
     * The headset's voice-assistant gesture starts a recording, just as the wake phrase
     * does. The phone may be locked and in a pocket: the gesture is allowed through the
     * lock screen for as long as this screen stays up.
     */
    private fun handleHeadsetGesture(intent: Intent?) {
        if (intent?.action !in HEADSET_GESTURES) return
        Logs.add("Headset gesture: ${intent?.action?.substringAfterLast('.')}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        graph.core.voice.onHeadsetGesture()
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Back in the foreground is the one place the microphone can be won back.
        if (WakeWordService.deaf) WakeWordService.restartListening(this)
        if (::graph.isInitialized) {
            graph.onResume()
            graph.core.cardOverlay.setAppInFront(true)
        }
    }

    override fun onPause() {
        graph.core.cardOverlay.setAppInFront(false)
        bridge.onPause()
        super.onPause()
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false") { result ->
            if (result != "true") {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
    }

    override fun onDestroy() {
        graph.dispose()
        uiScope.cancel()
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        /** What Android sends when a headset's voice-assistant gesture is made. */
        val HEADSET_GESTURES = setOf(Intent.ACTION_VOICE_COMMAND, "android.speech.action.VOICE_SEARCH_HANDS_FREE")

        /** When the page's render process was last lost, in this process. */
        @Volatile
        var lastRenderLossAt = -(5 * 60_000L)

        const val RENDER_LOSS_LOOP_MS = 5 * 60_000L
    }
}
