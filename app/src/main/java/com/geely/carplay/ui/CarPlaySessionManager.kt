package com.geely.carplay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayBluetooth
import com.shilapi.xcertplay.DiPlayBootstrap
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.compat.ContextCompat
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 页面共享同一真实会话, Activity 销毁不影响后台连接 */
object CarPlaySessionManager {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val commands = Executors.newSingleThreadExecutor()
    private val notifyListeners = Runnable { listeners.toList().forEach { it() } }
    private val listeners = mutableSetOf<() -> Unit>()
    private val logLines = ArrayDeque<String>()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var generation = 0
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var surface: Surface? = null

    var automaticConnectionAllowed = true
        private set
    @Volatile var isExiting = false
        private set
    var isConnected = false
        private set
    var isRunning = false
        private set
    var statusText = "尚未连接"
        private set
    var videoWidth = 1920
        private set
    var videoHeight = 720
        private set

    fun logs(): List<String> = logLines.toList()

    fun observe(listener: () -> Unit) {
        listeners += listener
        listener()
    }

    fun removeObserver(listener: () -> Unit) {
        listeners -= listener
    }

    fun connect(context: Context) {
        if (isExiting) return
        automaticConnectionAllowed = true
        context.startService(Intent(context, CarPlaySessionService::class.java))
    }

    fun disconnect(context: Context) {
        automaticConnectionAllowed = false
        stop()
        context.stopService(Intent(context, CarPlaySessionService::class.java))
    }

    fun cancelConnection(context: Context) {
        disconnect(context)
        statusText = context.getString(R.string.connect_cancelled)
        appendLog(statusText)
    }

    fun prepareExit(context: Context, onClosed: () -> Unit) {
        if (isExiting) return
        isExiting = true
        disconnect(context)
        var completed = false
        val complete = Runnable {
            if (!completed) {
                completed = true
                onClosed()
            }
        }
        main.postDelayed(complete, 15_000)
        worker.execute {
            main.post {
                main.removeCallbacks(complete)
                complete.run()
            }
        }
    }

    internal fun start(context: Context) {
        if (isRunning || isExiting) return
        val app = context.applicationContext
        val token = ++generation
        isRunning = true
        isConnected = false
        sessions.clear()
        logLines.clear()
        statusText = "正在初始化 CarPlay"
        changed()
        worker.execute {
            if (generation != token) return@execute
            var renderer: AndroidMediaSink? = null
            var next: CarPlayController? = null
            try {
                if (AirPlayPersistence.loadMfiTarget(app) == MfiTarget.LOCAL) DiPlayBootstrap.ensure(app)
                val identity = AirPlayPersistence.loadIdentity(app)
                val wireless = AirPlayPersistence.loadWirelessEnabled(app)
                val hotspotMode = AirPlayPersistence.loadWirelessHotspotMode(app)
                if (wireless && hotspotMode == WirelessHotspotMode.MANUAL) {
                    HotspotSettings.prepare(app) { message -> deliver(token) { appendLog(message) } }
                }
                if (generation != token) return@execute
                val deviceId = DiPlayBootstrap.deviceId(identity)
                val locationEnabled = AirPlayPersistence.loadLocationReportingEnabled(app)
                val mfiTarget = AirPlayPersistence.loadMfiTarget(app)
                val runtime = CarPlayRuntimeConfig(
                    mfiTarget = mfiTarget,
                    ch341Devices = listOf(UsbDeviceId(0x1a86, 0x5512)),
                    linuxI2cPath = AirPlayPersistence.loadMfiI2cPath(app),
                    remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(app).takeIf { it.length > 0 },
                    remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(app).takeIf { it.length > 0 },
                    identification = Iap2IdentificationConfig(
                        name = "Geely CarPlay",
                        modelIdentifier = "GKUI",
                        manufacturer = "Geely",
                        serialNumber = "GEELY-" + deviceId.replace(":", ""),
                        firmwareVersion = "1.0.0",
                        hardwareVersion = "1.0",
                        carPlayUsbInterfaceNumber = 3,
                        locationInformationEnabled = locationEnabled,
                    ),
                    label = "Geely CarPlay",
                    hostName = "geely-" + deviceId.replace(":", "").lowercase(Locale.US),
                    hostMac = deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray(),
                    transport = if (wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
                    wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(app),
                    wirelessHotspotMode = hotspotMode,
                    manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(app),
                    manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(app),
                    manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(app),
                    manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(app),
                    manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(app),
                    locationReportingEnabled = locationEnabled,
                )
                // 声明更大的物理尺寸让 iPhone 把 CarPlay 控件渲染得更小 画布分辨率保持不变
                val baseWidthPixels = 1920
                val baseHeightPixels = 720
                val declaredWidthMm = 350
                val declaredHeightMm = declaredWidthMm * baseHeightPixels / baseWidthPixels
                val baseDisplay = AirPlayDisplayConfig(
                    baseWidthPixels, baseHeightPixels, declaredWidthMm, declaredHeightMm,
                    AirPlayPersistence.loadFps(app),
                )
                deliver(token) {
                    appendLog("画布 ${baseWidthPixels}x$baseHeightPixels 声明物理尺寸 ${declaredWidthMm}x${declaredHeightMm}mm")
                }
                val resolutionDisplay = CarPlayDisplayScale.apply(baseDisplay, AirPlayPersistence.loadDisplayScaleTenths(app))
                var display = CarPlayUiScale.apply(
                    resolutionDisplay,
                    AirPlayPersistence.loadUiScalePercent(app),
                )
                if (AirPlayPersistence.loadUiScalePercent(app) < 100 && !supportsLargerDisplay(display, AirPlayPersistence.loadHevcEnabled(app))) {
                    display = resolutionDisplay
                    AirPlayPersistence.saveUiScalePercent(app, 100)
                    deliver(token) { appendLog("车机使用原生画布, 较小界面缩放已恢复默认值") }
                }
                val useCarMicrophone = AirPlayPersistence.loadCallMode(app) == 1
                com.shilapi.xcertplay.media.MicOwnerReleaser.enabled = useCarMicrophone
                if (useCarMicrophone) com.shilapi.xcertplay.media.MicOwnerReleaser.selfHeal()
                val microphone = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                deliver(token) { appendLog("音频能力: 麦克风可用=$microphone") }
                val config = AirPlayConfig(
                    deviceName = "Geely CarPlay",
                    deviceId = deviceId,
                    btMac = DiPlayBluetooth.localAddress(app) ?: deviceId,
                    sourceVersion = "950.7.1",
                    main = display,
                    rightHandDrive = AirPlayPersistence.loadRightHandDrive(app),
                    hevc = AirPlayPersistence.loadHevcEnabled(app),
                    microphone = microphone,
                    manufacturer = "Geely",
                    model = "GKUI",
                    oemLabel = "Geely",
                    icons = listOf(loadBrandIcon(app)),
                )
                renderer = AndroidMediaSink(
                    videoWidth = display.widthPixels,
                    videoHeight = display.heightPixels,
                    advancedAudioChannelMapping = AirPlayPersistence.loadAdvancedAudioChannelMapping(app),
                    audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(app),
                    mediaChannel = AirPlayPersistence.loadMediaAudioChannel(app),
                    navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(app),
                    geelyAudioRouting = AirPlayPersistence.loadGeelyAudioRouting(app),
                    geelyNavigationAlert = AirPlayPersistence.loadGeelyNavigationAlert(app),
                    context = app,
                    navigationStreamType = AirPlayPersistence.loadNavigationStreamType(app),
                    mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(app),
                    onAudioDiagnostic = { message -> deliver(token) { appendLog(message) } },
                )
                val listener = object : AirPlaySessionListener {
                    override fun onSessionActive(session: AirPlaySession) = deliver(token) {
                        sessions += session
                        commands.execute { session.setNightMode(ThemeStore.isNight) }
                        isConnected = true
                        statusText = "CarPlay 会话已建立"
                        appendLog(statusText)
                    }
                    override fun onSessionEnded(session: AirPlaySession) = deliver(token) {
                        sessions -= session
                        isConnected = sessions.isNotEmpty()
                        statusText = if (isConnected) "CarPlay 会话已建立" else "连接已结束, 等待重新连接"
                        appendLog(statusText)
                    }
                    override fun onVideoFrameRendered(session: AirPlaySession) = deliver(token) {
                        statusText = "CarPlay 画面已就绪"
                        changed()
                    }
                    override fun onDebugLog(message: String) = deliver(token) { appendLog(message) }
                    override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) = deliver(token) {
                        if (type == "modesChanged") {
                            val resources = params["resources"] as? List<*> ?: emptyList<Any?>()
                            for (resource in resources) {
                                val values = resource as? Map<*, *> ?: continue
                                val fields = listOf("resourceID", "entity", "owner", "transferType", "transferPriority", "takeConstraint", "borrowConstraint", "unborrowConstraint")
                                    .mapNotNull { key -> (values[key] as? Number)?.let { "$key=${it.toLong()}" } }
                                appendLog("音频资源协商: ${fields.joinToString(", ")}")
                            }
                        }
                    }
                    override fun onTransportError(message: String) = deliver(token) { appendLog("传输异常: $message") }
                    override fun onHostUiRequested(session: AirPlaySession) = deliver(token) {
                        app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                }
                next = CarPlayController(
                    context = app,
                    config = runtime,
                    airPlayConfig = config,
                    identity = identity,
                    pairings = AirPlayPersistence.loadPairings(app) { id, key -> AirPlayPersistence.savePairing(app, id, key) },
                    listener = listener,
                    media = CarPlayMediaEngine(renderer, microphone),
                    reportStatus = { status -> deliver(token) {
                        statusText = statusLabel(status)
                        appendLog(statusText)
                        if (status is CarPlayStatus.Failed) {
                            disconnectAfterFailure(app, statusText)
                        }
                    } },
                    loadPairRecord = { AirPlayPersistence.loadLockdownRecord(app) },
                    savePairRecord = { AirPlayPersistence.saveLockdownRecord(app, it) },
                    clearPairRecord = { AirPlayPersistence.clearLockdownRecord(app) },
                    locationProvider = if (locationEnabled) AndroidCarPlayLocationProvider(app) else null,
                    geelyBluetoothEnabled = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(app),
                )
                val preparedController = next
                val preparedSink = renderer
                main.post {
                    if (generation != token) {
                        release(preparedController, preparedSink)
                    } else {
                        controller = preparedController
                        sink = preparedSink
                        videoWidth = display.widthPixels
                        videoHeight = display.heightPixels
                        surface?.let { preparedSink.setSurface(110, it) }
                        try {
                            preparedController.start()
                        } catch (error: Exception) {
                            disconnectAfterFailure(app, "连接启动失败: ${error.message}")
                        }
                    }
                }
            } catch (error: Exception) {
                next?.close()
                next?.awaitClosed(10_000)
                renderer?.close()
                deliver(token) { disconnectAfterFailure(app, "连接失败: ${error.message}") }
            }
        }
    }

    internal fun stop() {
        generation++
        val previousController = controller
        val previousSink = sink
        controller = null
        sink = null
        sessions.clear()
        isRunning = false
        isConnected = false
        statusText = "已断开连接"
        changed()
        release(previousController, previousSink)
    }

    private fun release(previousController: CarPlayController?, previousSink: AndroidMediaSink?) {
        worker.execute {
            previousController?.close()
            if (previousController?.awaitClosed(10_000) == false) {
                android.util.Log.w("GeelyCarPlay", "控制器关闭等待超时")
            }
            previousSink?.close()
            com.shilapi.xcertplay.media.MicOwnerReleaser.restoreAll()
        }
    }

    fun syncTheme() {
        val active = sessions.toList()
        val night = ThemeStore.isNight
        commands.execute { active.forEach { it.setNightMode(night) } }
    }

    private fun disconnectAfterFailure(context: Context, message: String) {
        stop()
        statusText = message
        appendLog(message)
        context.stopService(Intent(context, CarPlaySessionService::class.java))
    }

    fun attachSurface(value: Surface) {
        surface = value
        sink?.setSurface(110, value)
    }

    fun detachSurface(value: Surface) {
        sink?.clearSurface(110, value)
        if (surface === value) surface = null
        controller?.sendTouch(emptyList())
    }

    fun sendTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        return controller?.sendTouch(CarPlayTouchMapper.contacts(event, width, height)) ?: false
    }

    fun saveReport(context: Context): File {
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "carplay-diagnostic.txt")
        file.writeText(logLines.joinToString("\n"))
        return file
    }

    private fun deliver(token: Int, action: () -> Unit) {
        main.post { if (generation == token && !isExiting) action() }
    }

    private fun appendLog(message: String) {
        val safeMessage = com.shilapi.xcertplay.DiagnosticRedactor.redact(message) ?: return
        android.util.Log.i("GeelyCarPlay", safeMessage)
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        logLines.addLast("$time  $safeMessage")
        while (logLines.size > 150) logLines.removeFirst()
        changed()
    }

    private fun changed() {
        main.removeCallbacks(notifyListeners)
        main.postDelayed(notifyListeners, 100)
    }

    private fun loadBrandIcon(context: Context): AirPlayIcon {
        val bitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.app_logo)
            ?: error("无法读取品牌图标")
        return try {
            val bytes = java.io.ByteArrayOutputStream()
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)) { "无法编码品牌图标" }
            AirPlayIcon(bitmap.width, bitmap.height, bytes.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    private fun supportsLargerDisplay(display: AirPlayDisplayConfig, hevc: Boolean): Boolean {
        // API18 无法查询硬件解码尺寸上限, 不向手机声明未经确认的大画布
        if (android.os.Build.VERSION.SDK_INT < 21) return false
        return runCatching {
            val mime = if (hevc) "video/hevc" else "video/avc"
            val decoder = com.shilapi.xcertplay.media.CodecCompat.videoDecoderFor(mime) ?: return false
            val video = decoder.getCapabilitiesForType(mime).videoCapabilities ?: return false
            video.areSizeAndRateSupported(display.widthPixels, display.heightPixels, display.fps.toDouble())
        }.getOrDefault(false)
    }

    private fun statusLabel(status: CarPlayStatus): String = when (status) {
        CarPlayStatus.DiscoveringMfi -> "正在查找 MFi 认证设备"
        CarPlayStatus.WaitingForMfi -> "等待 MFi 认证设备"
        CarPlayStatus.RequestingMfiPermission -> "请授权访问 MFi 设备"
        CarPlayStatus.MfiReady -> "MFi 认证已就绪"
        CarPlayStatus.StartingHotspot -> "正在启动热点"
        is CarPlayStatus.HotspotReady -> "热点已就绪"
        CarPlayStatus.WaitingForPairedIphone -> "等待已配对的 iPhone"
        CarPlayStatus.ConnectingBluetooth -> "正在建立蓝牙连接"
        CarPlayStatus.BluetoothReady -> "蓝牙连接已就绪"
        CarPlayStatus.WirelessIdentified -> "iAP2 设备识别完成"
        CarPlayStatus.WirelessAuthenticated -> "iAP2 认证完成"
        CarPlayStatus.WaitingForWifiJoin -> "等待 iPhone 加入车机热点"
        CarPlayStatus.RunningWireless -> "无线连接已建立, 等待 CarPlay 会话"
        CarPlayStatus.WirelessActive -> "无线 CarPlay 已连接"
        CarPlayStatus.DiscoveringIphone -> "正在查找 USB iPhone"
        CarPlayStatus.WaitingForIphone -> "请通过 USB 连接 iPhone"
        CarPlayStatus.RequestingIphonePermission -> "请授权访问 iPhone"
        CarPlayStatus.WaitingForReenumeration -> "等待 iPhone 切换 USB 模式"
        CarPlayStatus.SelectingConfiguration -> "正在配置 USB"
        CarPlayStatus.OpeningDataPaths -> "正在打开数据通道"
        CarPlayStatus.Pairing -> "正在配对, 请在 iPhone 上确认信任"
        CarPlayStatus.ConnectingControl -> "正在建立控制通道"
        CarPlayStatus.AttachingNetwork -> "正在建立 CarPlay 网络连接"
        CarPlayStatus.RunningControl -> "控制通道已就绪"
        CarPlayStatus.ControlEnded -> "控制通道已结束"
        is CarPlayStatus.Failed -> "连接失败: ${status.message}"
    }
}
