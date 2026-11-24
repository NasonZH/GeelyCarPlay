package com.geely.carplay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 页面共享同一真实会话, Activity 销毁不影响后台连接 */
object CarPlaySessionManager {
    /** 未开始连接时 statusText 的初值 用于区分有无连接进度 */
    const val STATUS_IDLE = "尚未连接"
    /** 链路看门狗检查间隔 只做时间戳比较 不访问蓝牙 */
    private const val LINK_CHECK_INTERVAL_MILLIS = 2_000L
    /** 手机无响应后判定断开的宽限时间 */
    private const val LINK_LOSS_TIMEOUT_MILLIS = 5_000L
    /** 会话建立多久仍无时间同步回包时记录一次日志 */
    private const val TIMING_SIGNAL_LOG_DELAY_MILLIS = 15_000L
    /** 链路诊断采样间隔 与音视频统计的 5 秒窗口对齐 */
    private const val LINK_DIAG_INTERVAL_MILLIS = 5_000L
    /** 重传计数读取间隔 降低 /proc 访问频率 */
    private const val RETRANS_SAMPLE_INTERVAL_MILLIS = 15_000L
    /** 连接后多久内自动暂停手机续播的音乐 超过这个时间视为用户主动播放 */
    private const val AUTO_PAUSE_WINDOW_MILLIS = 15_000L
    /** 一次通话内最多尝试拉回投屏页的次数 */
    private const val MAX_CALL_BRING_ATTEMPTS = 5
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val commands = Executors.newSingleThreadExecutor()
    private val notifyListeners = Runnable { listeners.toList().forEach { it() } }
    private val listeners = mutableSetOf<() -> Unit>()
    private val logLines = ArrayDeque<String>()
    /** 复用同一个格式化器 每条日志都新建 SimpleDateFormat 在车机上开销明显 */
    private val logTimeLock = Any()
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    /** 每秒允许写入的常规日志条数 超出部分按秒合并 避免连接阶段刷屏 */
    private const val LOG_BURST_LIMIT_PER_SECOND = 12
    private var logBurstWindowStartMs = 0L
    private var logBurstCount = 0
    private var logBurstSuppressed = 0
    private val sessions = java.util.concurrent.CopyOnWriteArraySet<AirPlaySession>()
    @Volatile private var generation = 0
    private var controller: CarPlayController? = null
    @Volatile private var sink: AndroidMediaSink? = null
    private var surface: Surface? = null
    private var surfaceFailureHandler: ((String) -> Unit)? = null
    private var surfaceFrameRendered = false
    private var linkWatchdog: ScheduledExecutorService? = null
    private var watchdogStartedAt = 0L
    /** 上一次检查时的通话状态 只在进入通话时拉回投屏页 */
    private var callActive = false
    /** 当前投屏页是否在前台 由 CarPlayActivity 生命周期更新 */
    @Volatile private var screenResumed = false
    private var lastAudioMode = -1
    private var callBringAttempts = 0
    @Volatile private var timingSignalMissingLogged = false
    /** /proc/net/snmp 可读性 读取被系统拒绝后不再每 5 秒重试 */
    private var tcpRetransReadable: Boolean? = null
    /** /proc 读取间隔 链路诊断仍每 5 秒采样 但重传计数每 15 秒读一次 */
    private var lastRetransReadAt = 0L
    private var lastRetransValue: Long? = null
    /** 链路诊断上一窗口的累计值 用于计算接收速率和重传增量 */
    private var lastDiagAt = 0L
    private var lastDiagRxBytes = 0L
    private var lastDiagRxPackets = 0L
    private var lastDiagRetransSegs = 0L
    /** 连接刚建立时是否还需要暂停手机自动续播的音乐 */
    @Volatile private var autoPausePending = false
    /** 连接时间线 全部使用 elapsedRealtime 毫秒 */
    @Volatile private var connectStartedAt = 0L
    private var sessionActiveAt = 0L
    private var screenEntryAt = 0L
    private var screenCreatedAt = 0L
    private var surfaceReadyAt = 0L
    private var firstFrameRenderedAt = 0L
    private var firstTextureUpdatedAt = 0L

    var automaticConnectionAllowed = true
        private set
    @Volatile var isExiting = false
        private set
    @Volatile var isConnected = false
        private set
    @Volatile var isRunning = false
        private set
    var statusText = STATUS_IDLE
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
        connectStartedAt = SystemClock.elapsedRealtime()
        sessionActiveAt = 0L
        screenEntryAt = 0L
        screenCreatedAt = 0L
        surfaceReadyAt = 0L
        firstFrameRenderedAt = 0L
        firstTextureUpdatedAt = 0L
        autoPausePending = false
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
                        name = "车机",
                        modelIdentifier = "GKUI",
                        manufacturer = "车机",
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
                    deviceName = "车机",
                    deviceId = deviceId,
                    btMac = DiPlayBluetooth.localAddress(app) ?: deviceId,
                    sourceVersion = "950.7.1",
                    main = display,
                    rightHandDrive = AirPlayPersistence.loadRightHandDrive(app),
                    hevc = AirPlayPersistence.loadHevcEnabled(app),
                    microphone = microphone,
                    manufacturer = "车机",
                    model = "GKUI",
                    oemLabel = "车机",
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
                    onVideoSurfaceFrameRendered = { renderedSurface -> deliver(token) {
                        if (surface === renderedSurface) {
                            surfaceFrameRendered = true
                            changed()
                        }
                    } },
                    onVideoSurfaceFailure = { failedSurface, reason -> deliver(token) {
                        if (surface === failedSurface) surfaceFailureHandler?.invoke(reason)
                    } },
                )
                val listener = object : AirPlaySessionListener {
                    override fun onSessionActive(session: AirPlaySession) = deliver(token) {
                        sessions += session
                        commands.execute { session.setNightMode(ThemeStore.isNight) }
                        isConnected = true
                        statusText = "CarPlay 会话已建立"
                        appendLog(statusText)
                        sessionActiveAt = SystemClock.elapsedRealtime()
                        appendLog("连接时间线: 会话建立=${timelineMillis(sessionActiveAt)}")
                        autoPausePending = true
                        main.postDelayed({ if (generation == token) autoPausePending = false }, AUTO_PAUSE_WINDOW_MILLIS)
                        rememberConnectedPhone(app)
                        if (wireless) startLinkWatchdog(app, token)
                    }
                    override fun onSessionEnded(session: AirPlaySession) = deliver(token) {
                        sessions -= session
                        isConnected = sessions.isNotEmpty()
                        statusText = if (isConnected) "CarPlay 会话已建立" else "连接已结束, 等待重新连接"
                        appendLog(statusText)
                    }
                    override fun onVideoFrameRendered(session: AirPlaySession) = deliver(token) {
                        statusText = "CarPlay 画面已就绪"
                        if (firstFrameRenderedAt == 0L) {
                            firstFrameRenderedAt = SystemClock.elapsedRealtime()
                            appendLog("连接时间线: 首帧渲染=${timelineMillis(firstFrameRenderedAt)}")
                        }
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
                // iOS 在连接车机时会自动续播上次的音频 检测到开始播放后暂停一次
                preparedController.playbackListener = { playing ->
                    if (playing) pauseAutoResumedPlayback()
                }
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
        stopLinkWatchdog()
        val previousController = controller
        val previousSink = sink
        surface?.let { previousSink?.clearSurface(110, it) }
        controller = null
        sink = null
        surfaceFrameRendered = false
        sessions.clear()
        isRunning = false
        isConnected = false
        autoPausePending = false
        statusText = "已断开连接"
        changed()
        release(previousController, previousSink)
    }

    /**
     * 无线会话建立后监控手机是否还在网络中
     * 蓝牙只在建立连接时用到 断开蓝牙后 CarPlay 仍走热点 因此不能用蓝牙判断
     * 时间同步每秒一次往返 是手机在线的最可靠信号 视频帧作为补充
     * 两者都静默超过 5 秒才判定断开 静止画面不会误判
     */
    private fun startLinkWatchdog(app: Context, token: Int) {
        stopLinkWatchdog()
        watchdogStartedAt = SystemClock.elapsedRealtime()
        timingSignalMissingLogged = false
        lastDiagAt = 0L
        lastDiagRxBytes = 0L
        lastDiagRxPackets = 0L
        lastDiagRetransSegs = 0L
        val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "carplay-link-watchdog").apply { isDaemon = true }
        }
        linkWatchdog = watchdog
        callActive = false
        watchdog.scheduleWithFixedDelay(
            { runCatching { checkWirelessLink(app, token) } },
            LINK_CHECK_INTERVAL_MILLIS,
            LINK_CHECK_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        watchdog.scheduleWithFixedDelay(
            { runCatching { checkCallState(app, token) } },
            LINK_CHECK_INTERVAL_MILLIS,
            LINK_CHECK_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        watchdog.scheduleWithFixedDelay(
            { runCatching { logLinkDiagnostics(app, token) } },
            LINK_DIAG_INTERVAL_MILLIS,
            LINK_DIAG_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun checkWirelessLink(app: Context, token: Int) {
        if (generation != token || !isRunning || !isConnected) return
        val timingAt = sessions.lastOrNull()?.timingPeerLastPacketAt ?: 0L
        // 没有时间同步回包时不做判断 交给协议层超时 避免静止画面被误判
        if (timingAt == 0L) {
            logMissingTimingSignalOnce()
            return
        }
        val lastVideoAt = sink?.lastVideoFrameAt ?: 0L
        val silentMillis = SystemClock.elapsedRealtime() - maxOf(timingAt, lastVideoAt)
        if (silentMillis <= LINK_LOSS_TIMEOUT_MILLIS) return
        main.post { finishLostLink(token, app, silentMillis) }
    }

    /** 通话开始时把投屏页拉回前台 车机通话界面退到后面 通话本身不受影响 */
    private fun checkCallState(app: Context, token: Int) {
        if (generation != token || !isRunning) {
            callActive = false
            callBringAttempts = 0
            return
        }
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = audio.mode
        val inCall = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
        if (mode != lastAudioMode) {
            lastAudioMode = mode
            appendLog("通话状态: mode=$mode inCall=$inCall connected=$isConnected screenResumed=$screenResumed")
        }
        if (!inCall || !isConnected || isExiting) {
            callActive = false
            callBringAttempts = 0
            return
        }
        callActive = true
        if (screenResumed) return
        if (callBringAttempts >= MAX_CALL_BRING_ATTEMPTS) return
        callBringAttempts++
        bringCarPlayToFront(app, token)
    }

    private fun bringCarPlayToFront(app: Context, token: Int) {
        main.post {
            if (generation != token || !isConnected || isExiting) return@post
            val intent = Intent(app, CarPlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
            val result = runCatching { app.startActivity(intent) }
            appendLog("通话中拉回投屏 attempt=$callBringAttempts success=${result.isSuccess}")
        }
    }

    /** 投屏页前后台状态 用于避免重复拉起同一个页面 */
    fun noteScreenResumed(resumed: Boolean) {
        screenResumed = resumed
    }

    /** 时间同步一直没回包时只记一次 便于判断检测为何没有生效 */    private fun logMissingTimingSignalOnce() {
        if (timingSignalMissingLogged) return
        if (SystemClock.elapsedRealtime() - watchdogStartedAt < TIMING_SIGNAL_LOG_DELAY_MILLIS) return
        timingSignalMissingLogged = true
        main.post { appendLog("未收到手机时间同步回包 断开检测交给协议层") }
    }

    /**
     * 每 5 秒记录一次链路状态 用于区分卡顿发生在 WiFi 手机端还是解码环节
     * 接收速率取本进程 uid 的流量差值 重传来自系统 TCP 计数 读不到时记为 0
     */
    private fun logLinkDiagnostics(app: Context, token: Int) {
        if (generation != token || !isRunning || !isConnected) return
        val now = SystemClock.elapsedRealtime()
        val uid = app.applicationInfo.uid
        val rxBytes = TrafficStats.getUidRxBytes(uid)
        val rxPackets = TrafficStats.getUidRxPackets(uid)
        val retransSegs = readTcpRetransSegs()
        val elapsed = if (lastDiagAt == 0L) 0L else now - lastDiagAt
        val deltaBytes = if (elapsed > 0) (rxBytes - lastDiagRxBytes).coerceAtLeast(0L) else 0L
        val deltaPackets = if (elapsed > 0) (rxPackets - lastDiagRxPackets).coerceAtLeast(0L) else 0L
        val deltaRetrans = if (elapsed > 0 && retransSegs != null) {
            (retransSegs - lastDiagRetransSegs).coerceAtLeast(0L)
        } else {
            0L
        }
        lastDiagAt = now
        lastDiagRxBytes = rxBytes
        lastDiagRxPackets = rxPackets
        lastDiagRetransSegs = retransSegs ?: 0L
        val wifi = runCatching {
            (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
        }.getOrNull()
        val frequency = if (wifi != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            wifi.frequency
        } else {
            0
        }
        val wifiText = if (wifi != null && frequency > 0) {
            "${frequency}MHz rssi=${wifi.rssi}dBm link=${wifi.linkSpeed}Mbps"
        } else {
            "未加入热点"
        }
        val kbps = if (elapsed > 0) deltaBytes * 8 / elapsed else 0L
        val videoSilent = sink?.lastVideoFrameAt?.takeIf { it != 0L }?.let { now - it }
        val silenceText = if (videoSilent == null) "视频静默=-" else "视频静默=${videoSilent}ms"
        val line = "链路诊断: wifi=$wifiText 接收=${kbps}kbps/${deltaPackets}包 重传=+$deltaRetrans $silenceText"
        val sessionDiagnostics = sessions.map { it.diagnosticSummary() }
        main.post {
            appendLog(line)
            sessionDiagnostics.forEach { appendLog("会话诊断: $it") }
        }
    }

    /** 系统 TCP 重传计数 增量持续升高说明链路在丢包重传 读不到时返回 null */
    private fun readTcpRetransSegs(): Long? {
        if (tcpRetransReadable == false) return null
        val now = SystemClock.elapsedRealtime()
        if (now - lastRetransReadAt < RETRANS_SAMPLE_INTERVAL_MILLIS) return lastRetransValue
        lastRetransReadAt = now
        val lines = runCatching { File("/proc/net/snmp").readLines() }.getOrNull()
        if (lines == null) {
            tcpRetransReadable = false
            return null
        }
        tcpRetransReadable = true
        val headerIndex = lines.indexOfFirst { it.startsWith("Tcp:") }
        if (headerIndex < 0) return null
        val names = lines[headerIndex].removePrefix("Tcp:").trim().split(Regex("\\s+"))
        val values = lines.getOrNull(headerIndex + 1)
            ?.removePrefix("Tcp:")?.trim()?.split(Regex("\\s+")) ?: return null
        val index = names.indexOf("RetransSegs")
        if (index < 0) return null
        lastRetransValue = values.getOrNull(index)?.toLongOrNull()
        return lastRetransValue
    }

    /** iOS 在连接车机时会自动续播上次的音频 这里在检测到开始播放后暂停一次 */
    private fun pauseAutoResumedPlayback() {
        if (!autoPausePending) return
        val session = sessions.lastOrNull() ?: return
        autoPausePending = false
        commands.execute { session.sendMedia(CarPlayMediaButton.PAUSE) }
        main.post { appendLog("已暂停手机自动续播的音乐") }
    }

    private fun finishLostLink(token: Int, app: Context, silentMillis: Long) {
        if (generation != token || !isRunning || !isConnected) return
        stop()
        statusText = "连接已断开"
        appendLog("$statusText: 手机无响应 ${silentMillis / 1000} 秒")
        app.stopService(Intent(app, CarPlaySessionService::class.java))
    }

    private fun stopLinkWatchdog() {
        linkWatchdog?.shutdownNow()
        linkWatchdog = null
    }

    /** 只有会话真正建立才写入历史 供首页快速连接使用 */
    private fun rememberConnectedPhone(app: Context) {
        val address = DiPlayPreferences.phoneAddress(app) ?: return
        DiPlayPreferences.recordConnectedPhone(app, address, DiPlayPreferences.phoneName(app))
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

    /** 记录会话建立后立即进入投屏的决定 */
    fun noteScreenEntry() {
        if (!isRunning) return
        screenEntryAt = SystemClock.elapsedRealtime()
        appendLog("连接时间线: 会话建立后立即进入投屏=${timelineMillis(screenEntryAt)}")
    }

    fun noteScreenCreated() {
        if (!isRunning || screenEntryAt == 0L || screenCreatedAt != 0L) return
        screenCreatedAt = SystemClock.elapsedRealtime()
        appendLog("连接时间线: 投屏页创建=${timelineMillis(screenCreatedAt)}")
    }

    fun noteSurfaceReady() {
        if (!isRunning || screenEntryAt == 0L || surfaceReadyAt != 0L) return
        surfaceReadyAt = SystemClock.elapsedRealtime()
        appendLog("连接时间线: Surface 就绪=${timelineMillis(surfaceReadyAt)}")
    }

    fun noteFirstTextureUpdated() {
        // TextureView 创建和布局时也会回调 此时还没有任何视频帧 必须等首帧真正渲染后再记录
        if (!isRunning || screenEntryAt == 0L || firstFrameRenderedAt == 0L || firstTextureUpdatedAt != 0L) return
        firstTextureUpdatedAt = SystemClock.elapsedRealtime()
        appendLog(timelineSummary())
    }

    fun noteSurfaceViewFrameSubmitted() {
        if (!isRunning || screenEntryAt == 0L) return
        appendLog("连接时间线: SurfaceView 首帧已提交 实际呈现时间未测量")
        appendLog(timelineSummary())
    }

    fun hasSurfaceFrameRendered(): Boolean = surfaceFrameRendered

    fun noteVideoOutput(message: String) {
        appendLog("视频显示: $message")
    }

    fun attachSurface(value: Surface, onFailure: (String) -> Unit) {
        surfaceFrameRendered = false
        surfaceFailureHandler = onFailure
        surface = value
        sink?.setSurface(110, value)
        appendLog("Surface 诊断: 挂载 valid=${value.isValid} sessions=${sessions.map { it.diagnosticId }}")
    }

    fun detachSurface(value: Surface) {
        sink?.clearSurface(110, value)
        val current = surface === value
        if (current) {
            surface = null
            surfaceFailureHandler = null
            surfaceFrameRendered = false
        }
        appendLog("Surface 诊断: 分离 current=$current valid=${value.isValid} sessions=${sessions.map { it.diagnosticId }}")
        controller?.sendTouch(emptyList())
    }

    fun sendTouch(event: MotionEvent, width: Int, height: Int): Boolean {
        return controller?.sendTouch(CarPlayTouchMapper.contacts(event, width, height)) ?: false
    }

    /** 日志快照在调用线程生成 文件写入与结果回调分别在工作线程和主线程完成 */
    fun saveReport(context: Context, onSaved: (File?, Throwable?) -> Unit) {
        val snapshot = logLines.joinToString("\n")
        val appContext = context.applicationContext
        worker.execute {
            val result = runCatching {
                val directory = appContext.getExternalFilesDir(null) ?: appContext.filesDir
                val file = File(directory, "carplay-diagnostic.txt")
                file.writeText(snapshot)
                file
            }
            main.post { onSaved(result.getOrNull(), result.exceptionOrNull()) }
        }
    }

    private fun deliver(token: Int, action: () -> Unit) {
        main.post { if (generation == token && !isExiting) action() }
    }

    private fun appendLog(message: String) {
        if (!allowLogLine(message)) return
        writeLogLine(message)
    }

    /** 高频阶段限制写入速率 失败/错误类关键行与详细日志模式始终保留 */
    private fun allowLogLine(message: String): Boolean {
        if (com.shilapi.xcertplay.diagnostics.DiagnosticLog.verbose) return true
        if (isCriticalLog(message)) return true
        val now = SystemClock.elapsedRealtime()
        if (now - logBurstWindowStartMs >= 1_000L) {
            logBurstWindowStartMs = now
            logBurstCount = 0
            if (logBurstSuppressed > 0) {
                writeLogLine("日志采样: 上一秒省略 ${logBurstSuppressed} 条高频日志")
                logBurstSuppressed = 0
            }
        }
        if (logBurstCount < LOG_BURST_LIMIT_PER_SECOND) {
            logBurstCount++
            return true
        }
        logBurstSuppressed++
        return false
    }

    private fun isCriticalLog(message: String): Boolean =
        message.contains("失败") || message.contains("错误") || message.contains("异常") ||
            message.contains("超时") || message.contains("断开") || message.contains("取消") ||
            message.contains("通话") ||
            message.startsWith("连接时间线") || message.startsWith("会话诊断") ||
            message.startsWith("链路诊断") || message.startsWith("画布 ") ||
            message.startsWith("音频能力")

    private fun writeLogLine(message: String) {
        val safeMessage = com.shilapi.xcertplay.DiagnosticRedactor.redact(message) ?: return
        android.util.Log.i("GeelyCarPlay", safeMessage)
        val time = synchronized(logTimeLock) { logTimeFormat.format(Date()) }
        logLines.addLast("$time  $safeMessage")
        while (logLines.size > 150) logLines.removeFirst()
        changed()
    }

    /** 距离连接开始的毫秒数 未记录时显示占位符 */
    private fun timelineMillis(at: Long): String {
        if (at == 0L || connectStartedAt == 0L) return "-"
        return "${at - connectStartedAt}ms"
    }

    /** 首次显示画面后汇总各阶段耗时 便于判断卡顿发生在哪一段 */
    private fun timelineSummary(): String {
        return "连接时间线汇总: 会话=${timelineMillis(sessionActiveAt)} " +
            "视频参数=${timelineMillis(sink?.firstVideoConfigAt ?: 0L)} " +
            "首包=${timelineMillis(sink?.firstVideoFrameAt ?: 0L)} " +
            "进入=${timelineMillis(screenEntryAt)} " +
            "页面=${timelineMillis(screenCreatedAt)} " +
            "Surface=${timelineMillis(surfaceReadyAt)} " +
            "首帧=${timelineMillis(firstFrameRenderedAt)} " +
            "显示=${timelineMillis(firstTextureUpdatedAt)}"
    }

    private fun changed() {
        main.removeCallbacks(notifyListeners)
        main.postDelayed(notifyListeners, 100)
    }

    private fun loadBrandIcon(context: Context): AirPlayIcon {
        val bitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.carplay_oem_icon)
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
