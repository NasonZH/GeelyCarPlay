package com.shilapi.xcertplay.orchestration

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.compat.systemService
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.transport.AnwBluetoothBackend
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2LocationRequest
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object BluetoothReady : CarPlayStatus()
    data object WirelessIdentified : CarPlayStatus()
    data object WirelessAuthenticated : CarPlayStatus()
    data object WaitingForWifiJoin : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String, val wifiResetRequired: Boolean = false) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/**
 * Wires the complete wired or wireless CarPlay path: local MFi authentication, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * All blocking USB work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val localMfi: LocalMfiAuthenticationClient,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
    private val vehicleStatusProvider: com.shilapi.xcertplay.transport.VehicleStatusProvider? = null,
    private val geelyBluetoothEnabled: Boolean = false,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        BydNavigationOutputs.start(context.applicationContext)
        BydNavigationOutputs.setClusterStreamControl(::applyClusterUi)
    }

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val diagnosticAttempt = diagnosticAttempts.incrementAndGet()
    private val diagnosticRun = AtomicInteger()
    private val usbManager = context.systemService(UsbManager::class.java, "usb")
        ?: error("USB service unavailable")
    private val bluetoothAdapter =
        appContext.systemService(BluetoothManager::class.java, "bluetooth")?.adapter
    private val iphoneHost = IphoneUsbHost(
        appContext,
        usbManager,
        if (config.iphoneDevices.isNotEmpty()) {
            IphoneUsbMatcher(config.iphoneDevices)
        } else {
            IphoneUsbMatcher.appleVendor()
        },
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val controlExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "xcertplay-controller-control").apply { isDaemon = true }
    }
    private val teardownExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "xcertplay-controller-teardown").apply { isDaemon = true }
    }
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val inputLock = Any()
    private var pendingTouchMove: PendingTouchMove? = null
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    private var lastReportedStatus: CarPlayStatus? = null

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var mfiClient: MfiAuthenticator? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    private val clusterUiLock = Any()
    private var clusterUiStream: Pair<AirPlaySession, Int>? = null
    private var clusterUiShown = true
    private val playbackStatus = com.shilapi.xcertplay.media.CarPlayPlaybackStatus()

    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null

    /** Told when retained iPhone now-playing metadata changes; may run on any thread. */
    @Volatile var nowPlayingListener: ((com.shilapi.xcertplay.media.CarPlayNowPlaying) -> Unit)? = null

    /** Told when an iAP2 Now Playing artwork transfer completes; may run on the link worker. */
    @Volatile var artworkListener: ((Int, ByteArray) -> Unit)? = null

    /** Video in car; set before [start] to offer it to the iPhone (with AirPlayConfig.videoInCar). */
    @Volatile var videoListener: CarPlayVideoListener? = null
    @Volatile private var videoGate: VideoInCarGate? = null

    /** Answers the iPhone on a video in car remote control session; a network write, any thread. */
    fun sendVideoMessage(streamId: Long, message: Map<String, Any?>): Boolean =
        activeSession?.sendRemoteControlMessage(streamId, message) ?: false

    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BlockingDuplexByteStream? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var wirelessLocationRequest = Iap2LocationRequest()
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            if (activeSession !== session) {
                BydNavigationOutputs.start(appContext)
                com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(true)
                // The gear may have changed since /info.
                if (videoListener != null) session.setVideoPlaybackAllowed(VideoInCar.allowed)
            }
            activeSession = session
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) {
                activeSession = null
                BydNavigationOutputs.endNow()
                com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(false)
                videoListener?.onVideoSessionEnded()
                synchronized(playbackStatus) {
                    val wasPlaying = playbackStatus.playing
                    playbackStatus.clearAll()?.let { it to wasPlaying }
                }?.let { (cleared, wasPlaying) ->
                    nowPlayingListener?.invoke(cleared)
                    if (wasPlaying) playbackListener?.invoke(false)
                }
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: show the head unit's own menu, like its Home button.
        // The session keeps running in the background, so returning to DiPlay resumes CarPlay.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; opening the head-unit home screen")
            runCatching {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { debugLog("Car home screen could not open: ${it.javaClass.simpleName}") }
            uiListener?.onHostUiRequested(session)
        }

        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            if (activeSession === session) videoListener?.onVideoMessage(streamId, message)
        }

        override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car's video player")
            if (activeSession === session) videoListener?.onVideoUiRequested()
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() = synchronized(lifecycleLock) {
        if (closed) return@synchronized
        connectionDiagnostic("start transport=${config.transport}")
        videoListener?.let { listener ->
            videoGate = VideoInCarGate(listener::readParked) { allowed ->
                val sent = activeSession?.setVideoPlaybackAllowed(allowed)
                debugLog("video in car allowed=$allowed sent=${sent ?: "no session"}")
                listener.onVideoAllowedChanged(allowed)
            }.also { it.start() }
        }
        if (config.transport == CarPlayTransport.WIRED) {
            permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
            attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
        }
        startMfi()
    }

    /** 重新启用本地 MFi 认证 */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        mfiClient = null
        startMfi()
    }

    /** 使用已加载的本地认证重新连接 iPhone */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (mfiClient == null) {
            startMfi()
        } else if (config.transport == CarPlayTransport.WIRELESS) {
            restartWireless()
        } else {
            startIphone()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean = enqueueInput { session, queuedAtNs ->
        session.sendTouch(contacts, queuedAtNs)
    }

    fun sendTouchMove(contacts: List<AirPlayContact>): Boolean = synchronized(inputLock) {
        if (closed) return false
        val session = activeSession ?: return false
        val queuedAtNs = System.nanoTime()
        val previous = pendingTouchMove
        if (previous != null && previous.session === session) {
            previous.contacts = contacts
            previous.queuedAtNs = queuedAtNs
            session.recordCoalescedTouchMove()
            return true
        }

        val move = PendingTouchMove(session, contacts, queuedAtNs)
        pendingTouchMove = move
        val queued = submitInput {
            synchronized(inputLock) {
                if (pendingTouchMove === move) pendingTouchMove = null
            }
            if (!closed && activeSession === session) {
                session.sendTouch(move.contacts, move.queuedAtNs, move.firstQueuedAtNs)
            }
        }
        if (!queued) pendingTouchMove = null
        queued
    }

    /** Opens Siri on the iPhone, as the car's voice button does in CarPlay. */
    fun requestSiri(): Boolean = enqueueInput { session, _ -> session.invokeSiri() }

    /** Sends one CarPlay media-button press. */
    fun sendMediaButton(index: Int): Boolean = enqueueInput { session, _ -> session.sendMedia(index) }

    private fun enqueueInput(send: (AirPlaySession, Long) -> Unit): Boolean = synchronized(inputLock) {
        if (closed) return false
        val session = activeSession ?: return false
        // 手势边界和按键保持原顺序 后续移动事件不能覆盖它们之前的坐标
        pendingTouchMove = null
        val queuedAtNs = System.nanoTime()
        submitInput {
            if (!closed && activeSession === session) send(session, queuedAtNs)
        }
    }

    private fun submitInput(send: () -> Unit): Boolean = try {
        touchExecutor.execute { send() }
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        false
    }

    private class PendingTouchMove(
        val session: AirPlaySession,
        var contacts: List<AirPlayContact>,
        val firstQueuedAtNs: Long,
    ) {
        var queuedAtNs = firstQueuedAtNs
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
        }
        val teardownStarted = System.nanoTime()
        connectionDiagnostic("teardown begin transport=${config.transport}")
        videoGate?.close()
        BydNavigationOutputs.endNow()
        com.shilapi.xcertplay.glance.CarPlayGlance.setConnected(false)
        BydNavigationOutputs.clearClusterStreamControl(::applyClusterUi)
        closeReceivers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        permissionPollGeneration += 1
        controlExecutor.shutdownNow()
        synchronized(inputLock) {
            pendingTouchMove = null
            touchExecutor.shutdownNow()
        }
        tunnelExecutor.shutdownNow()
        val service = vpnService
        unbindVpn()
        teardownExecutor.execute {
            try {
                if (config.transport == CarPlayTransport.WIRELESS) {
                    closeBestEffort("wireless stack") { closeWirelessStack(service) }
                } else {
                    closeBestEffort("CSM") { csm?.close() }
                    csm = null
                }
                closeBestEffort("USBMUX") { mux?.close() }
                mux = null
                if (config.transport == CarPlayTransport.WIRED) {
                    closeBestEffort("VPN/NCM") { service?.detach() }
                }
                mfiClient = null
                wirelessIdentification = null
                wirelessAirPlayEndpoint = null
                closeBestEffort("location provider") { locationProvider?.close() }
            } finally {
                executor.shutdownNow()
                val executorTerminated = awaitExecutorTermination(executor)
                val controlTerminated = awaitExecutorTermination(controlExecutor)
                connectionDiagnostic(
                    "teardown end elapsedMs=${elapsedMillis(teardownStarted)} " +
                        "executorTerminated=$executorTerminated controlTerminated=$controlTerminated",
                )
                teardownComplete.countDown()
            }
        }
        teardownExecutor.shutdown()
    }

    private fun awaitExecutorTermination(worker: ExecutorService): Boolean = try {
        worker.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    // Each new cluster stream starts with the map drawn (its initialURL); send only real changes.
    private fun applyClusterUi(shown: Boolean) = synchronized(clusterUiLock) {
        val session = activeSession ?: return@synchronized
        val stream = session.clusterStream.takeIf { it > 0 } ?: return@synchronized
        if (clusterUiStream != session to stream) {
            clusterUiStream = session to stream
            clusterUiShown = true
        }
        if (shown == clusterUiShown) return@synchronized
        if (session.setClusterUiShown(shown)) {
            clusterUiShown = shown
            debugLog("Cluster map: ${if (shown) "showUI, the cluster shows the map" else "stopUI, the cluster hides the map"}")
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // HUD (SOME/IP) and cluster (AMap broadcast) keep separate state so one failing cannot stall the other.
    private fun onRouteFrame(frame: com.shilapi.xcertplay.iap2.wire.Iap2Frame) {
        BydNavigationOutputs.onFrame(frame)
        com.shilapi.xcertplay.glance.CarPlayGlance.onFrame(frame)
        synchronized(playbackStatus) {
            val previousPlaying = playbackStatus.playing
            playbackStatus.acceptUpdate(frame)?.let { it to (it.playing != previousPlaying) }
        }?.let { (update, playingChanged) ->
            nowPlayingListener?.invoke(update)
            if (playingChanged) playbackListener?.invoke(update.playing)
        }
    }

    private fun onArtworkTransfer(transfer: com.shilapi.xcertplay.transport.Iap2ArtworkTransfer) {
        debugLog("iap2 artwork transfer id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
        artworkListener?.invoke(transfer.id, transfer.bytes)
    }

    private fun startMfi() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        debugLog("mfi discovery backend=LocalOffline")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = localMfi.withSignatureListener { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiClient = client
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed.
                fail(error)
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless() {
        diagnosticRun.incrementAndGet()
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.WIRELESS
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        onStatus(CarPlayStatus.StartingHotspot)
        val generation = wirelessGeneration.incrementAndGet()
        executor.execute {
            runWireless(generation)
        }
    }

    private fun restartWireless() {
        val generation = wirelessGeneration.incrementAndGet()
        submitWirelessControl(generation) {
            closeWirelessStack()
            if (!closed && generation == wirelessGeneration.get()) startWireless()
        }
    }

    private fun submitWirelessControl(generation: Int, action: () -> Unit) {
        if (closed || generation != wirelessGeneration.get()) return
        try {
            controlExecutor.execute {
                if (!closed && generation == wirelessGeneration.get()) action()
            }
        } catch (error: RejectedExecutionException) {
            if (!closed) throw error
        }
    }

    private fun runWireless(generation: Int) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            closeWirelessStack()
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val mfi = mfiClient
                ?: throw IOException("本地 MFi 认证未就绪")
            val hotspotInfo = startWirelessHotspot(generation)
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val startedHotspot = hotspot
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            val hostAddress = hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)

            val vendorBackend = if (geelyBluetoothEnabled) AnwBluetoothBackend(appContext) else null
            val adapter = if (vendorBackend == null) bluetoothAdapter
                ?: throw IOException("Bluetooth adapter is unavailable") else null
            if (adapter != null && !adapter.isEnabled) throw IOException("Bluetooth is not enabled")
            val device = adapter?.let { selectWirelessBluetoothDevice(it) }
            val vendorDevice = vendorBackend?.let { backend ->
                val paired = backend.pairedDevices()
                config.wirelessBluetoothDeviceAddress?.let { selected ->
                    paired.firstOrNull { it.address.equals(selected, ignoreCase = true) }
                        ?: throw IOException("Selected iPhone is not in the H52 factory paired list")
                } ?: paired.filter { it.name.contains("iPhone", ignoreCase = true) }.singleOrNull()
                    ?: paired.singleOrNull()
                    ?: throw IOException("Choose an iPhone from the H52 factory paired list first")
            }
            val targetAddress = vendorDevice?.address ?: device!!.address
            val hostBluetoothMac = vendorBackend?.localAddress() ?: accessoryBluetoothMac(adapter!!)
            debugLog("wireless Bluetooth backend=${if (vendorBackend != null) "H52-ANW" else "Android"}")
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )

            val listenerPort = attachWirelessAirPlay(
                generation, hostAddress, hostAddressText, hotspotInfo.interfaceName, wirelessAirPlayConfig, mfi,
            ) ?: return
            val channel = resolveBluetoothBootstrap(generation, vendorBackend, targetAddress, device) ?: return
            val identification = config.identification.copy(
                wireless = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid),
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOf(hostAddressText),
                airPlayPort = listenerPort,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
            )
            wirelessIdentification = identification
            wirelessAirPlayEndpoint = endpoint
            wirelessLocationRequest = Iap2LocationRequest()
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            runWirelessControlLoop(generation, channel, mfi, identification, endpoint)
        } catch (error: Throwable) {
            if (closed || generation != wirelessGeneration.get()) {
                return
            }
            if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                closeWirelessStack()
                if (error is Error) throw error
                fail(error)
            }
        }
    }

    private fun attachWirelessAirPlay(
        generation: Int,
        hostAddress: InetAddress,
        hostAddressText: String,
        interfaceName: String?,
        wirelessAirPlayConfig: AirPlayConfig,
        mfi: MfiAuthenticator,
    ): Int? {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService()
            ?: throw IOException("Could not bind the CarPlay AirPlay service")
        when (
            val result = service.attachWireless(
                bindAddress = hostAddress,
                config = wirelessAirPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfi,
                listener = wirelessSessionListener(generation),
                media = media,
            )
        ) {
            CarPlayVpnService.AttachResult.Started -> Unit
            CarPlayVpnService.AttachResult.AlreadyStarted ->
                throw IOException("Wireless AirPlay transport is already attached")
            is CarPlayVpnService.AttachResult.Failed ->
                throw IOException(result.message)
        }
        val listenerPort = service.boundPort() ?: wirelessAirPlayConfig.port
        val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenerPort)
        debugLog(
            "wireless AirPlay listener attached bind=$hostAddressText " +
                "port=$listenerPort" +
                (if (listenerPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else ""),
        )
        if (isStaleWirelessRun(generation)) {
            closeWirelessStack()
            return null
        }

        val bonjourClient = CarPlayBonjour(
            context = appContext,
            config = advertisedAirPlayConfig,
            identity = identity,
            advertisedHost = hostAddress.hostAddress,
            // Bind discovery and its connect probe to the same AP/address family as AirPlay.
            // The car hotspot previously used system NSD, which could resolve another interface
            // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
            useInterfaceMdns = true,
            onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
        )
        bonjour = bonjourClient
        bonjourClient.start()
        debugLog("wireless Bonjour services started mode=interface iface=${interfaceName ?: "unknown"}")
        if (isStaleWirelessRun(generation)) {
            closeWirelessStack()
            return null
        }
        return listenerPort
    }

    private fun resolveBluetoothBootstrap(
        generation: Int,
        vendorBackend: AnwBluetoothBackend?,
        targetAddress: String,
        device: BluetoothDevice?,
    ): Iap2Session? {
        onStatus(CarPlayStatus.ConnectingBluetooth)
        debugLog("wireless Bluetooth connecting backend=${if (vendorBackend != null) "H52-ANW" else "Android"}")
        val stream: BlockingDuplexByteStream = if (vendorBackend != null) {
            vendorBackend.connect(targetAddress, UUID.fromString(IAP2_IPHONE_UUID), onTrace = ::debugLog) {
                isStaleWirelessRun(generation)
            }.also { bluetoothStream = it }
        } else {
            val standardDevice = device!!
            val socket = standardDevice.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                .also { bluetoothSocket = it }
            logBluetoothConnectionSnapshot(standardDevice, "before-connect")
            val bluetoothStarted = System.nanoTime()
            try {
                connectBluetoothSocket(socket, standardDevice.address)
                connectionDiagnostic("Bluetooth connect completed elapsedMs=${elapsedMillis(bluetoothStarted)}")
            } catch (error: Throwable) {
                connectionDiagnostic("Bluetooth connect failed elapsedMs=${elapsedMillis(bluetoothStarted)} failureClass=${diagnosticFailureClass(error)}")
                logBluetoothConnectionSnapshot(standardDevice, "after-failure")
                throw error
            }
            BluetoothRfcommDuplexStream(socket).also { bluetoothStream = it }
        }
        if (isStaleWirelessRun(generation)) {
            closeWirelessStack()
            return null
        }
        onStatus(CarPlayStatus.BluetoothReady)
        val channel = Iap2Session.openWireless(
            stream,
            traceContext = if (vendorBackend != null) "wireless-h52-anw" else "wireless-rfcomm",
            onTrace = ::debugLog,
            onArtwork = ::onArtworkTransfer,
        ).also { csm = it }
        debugLog("wireless iAP2 CSM channel opened over RFCOMM")
        if (isStaleWirelessRun(generation)) {
            closeWirelessStack()
            return null
        }
        return channel
    }

    private fun runWirelessControlLoop(
        generation: Int,
        channel: Iap2Session,
        mfi: MfiAuthenticator,
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
    ) {
        onStatus(CarPlayStatus.RunningWireless)
        debugLog("wireless Bluetooth iAP2 control starting")
        val result = Iap2WirelessControlClient(
            session = channel,
            mfi = Iap2MfiAuthenticationClient(mfi),
        ).run(
            identification = identification,
            endpoint = endpoint,
            timeoutMillis = controlLoopTimeoutMillis(),
            locationProvider = locationProvider,
            vehicleStatusProvider = vehicleStatusProvider,
            locationRequest = wirelessLocationRequest,
            onIncoming = ::onRouteFrame,
            onStage = { stage ->
                if (!isStaleWirelessRun(generation) && !wirelessActiveReported.get()) {
                    when (stage) {
                        com.shilapi.xcertplay.transport.Iap2WirelessControlStage.IDENTIFIED ->
                            onStatus(CarPlayStatus.WirelessIdentified)
                        com.shilapi.xcertplay.transport.Iap2WirelessControlStage.AUTHENTICATED ->
                            onStatus(CarPlayStatus.WirelessAuthenticated)
                        com.shilapi.xcertplay.transport.Iap2WirelessControlStage.WIFI_CONFIG_SENT,
                        com.shilapi.xcertplay.transport.Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT ->
                            onStatus(CarPlayStatus.WaitingForWifiJoin)
                        else -> Unit
                    }
                }
            },
            onProgress = ::debugLog,
        )
        if (isStaleWirelessRun(generation)) {
            closeWirelessStack()
            return
        }
        when (result.terminal) {
            Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                debugLog(
                    "wireless RFCOMM EOF: iap2State=${result.stage} " +
                        "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                        "transportIdentifier=${result.transportNotificationSeen} " +
                        "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                        "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                        "handoffRequested=${wirelessHandoffRequested.get()} " +
                        "tunnelReady=${wirelessTunnelReady.get()} " +
                        "wirelessActive=${wirelessActiveReported.get()}",
                )
                if (!wirelessActiveReported.get()) {
                    val handoffInProgress = isWirelessHandoffInProgress(
                        handoffRequested = wirelessHandoffRequested.get(),
                        tunnelActive = wirelessTunnelChannel != null,
                        sessionActive = activeSession != null,
                    )
                    if (!handoffInProgress) {
                        throw IOException(
                            "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                        )
                    }
                    debugLog(
                        "wireless Bluetooth bootstrap closed during handoff; " +
                            "keeping the Wi-Fi AirPlay tunnel alive",
                    )
                }
            }
            Iap2WirelessControlTerminal.TIMED_OUT ->
                if (!wirelessActiveReported.get()) {
                    onStatus(CarPlayStatus.ControlEnded)
                }
        }
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed || config.transport != CarPlayTransport.WIRELESS) return false
        val identification = wirelessIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiClient ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        val generation = wirelessGeneration.get()
        debugLog("wireless iAP2 tunnel control starting")
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        vehicleStatusProvider = vehicleStatusProvider,
                        // The iPhone asks for location only on the Bluetooth link (see Iap2LocationRequest).
                        locationRequest = wirelessLocationRequest,
                        continueLocationRequest = true,
                        onReady = {
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = ::onRouteFrame,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(generation: Int): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.activate(generation, session)
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                wirelessConnectionProof.rendered(generation, session)
                uiListener?.onVideoFrameRendered(session)
            }

            // Passed on explicitly: without these the car's video player never opened over Wi-Fi.
            override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) =
                sessionListener.onRemoteControlMessage(session, streamId, message)

            override fun onVideoPlaybackUiRequested(session: AirPlaySession) =
                sessionListener.onVideoPlaybackUiRequested(session)
        }

    private fun onWirelessTunnelReady(generation: Int) {
        if (
            closed ||
            phase != Phase.WIRELESS ||
            generation != wirelessGeneration.get()
        ) {
            return
        }
        wirelessTunnelReady.set(true)
        wirelessConnectionProof.authenticated(generation)
        debugLog(
            "wireless iAP2 tunnel ready; " +
                "handoffRequested=${wirelessHandoffRequested.get()}",
        )
        maybeCompleteWirelessHandoff()
    }

    private fun maybeCompleteWirelessHandoff() {
        if (!wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
        if (!wirelessActiveReported.compareAndSet(false, true)) return
        val generation = wirelessGeneration.get()
        submitWirelessControl(generation) {
            if (isStaleWirelessRun(generation)) return@submitWirelessControl
            debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
            closeBluetoothBootstrapTransport()
            if (!isStaleWirelessRun(generation)) onStatus(CarPlayStatus.WirelessActive)
        }
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                submitWirelessControl(generation) {
                    if (isStaleWirelessRun(generation) || wirelessActiveReported.get()) {
                        return@submitWirelessControl
                    }
                    if (wirelessConnectionProof.hasRenderedFrame(generation)) {
                        // 已出画的会话可以没有 type-130 控制通道 超时也保留该会话
                        debugLog(
                            "wireless handoff tunnel iAP2 unavailable after first video frame; " +
                                "preserving the active CarPlay session",
                        )
                        onStatus(CarPlayStatus.WirelessActive)
                        return@submitWirelessControl
                    }
                    closeWirelessStack()
                    if (!isStaleWirelessRun(generation)) {
                        fail(IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"))
                    }
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    private fun startIphone() {
        diagnosticRun.incrementAndGet()
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed) return
        try {
            when (val request = iphoneHost.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration++
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        // API 18 exposes the active USB layout only after opening a connection;
                        // inspect it off the main thread and keep the same re-enumeration policy.
                        executor.execute {
                            val configurationReady = try {
                                iphoneHost.hasCarPlayConfiguration(result.device)
                            } catch (error: Exception) {
                                debugLog("USB configuration inspection failed: ${error.message}")
                                false
                            }
                            mainHandler.post {
                                if (closed) return@post
                                connectionDiagnostic(
                                    "USB configuration ready=$configurationReady " +
                                        "api=${android.os.Build.VERSION.SDK_INT} " +
                                        "reenumerationAttempts=$reenumerationAttempts " +
                                        "action=${when {
                                            configurationReady -> "reuse-descriptors"
                                            reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS -> "request-transition"
                                            else -> "reject-missing-configuration"
                                        }}",
                                )
                                if (configurationReady) {
                                    openDataPaths(result.device)
                                } else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {
                                    beginReenumeration(result.device)
                                } else {
                                    fail(
                                        IphoneUsbException.Protocol(
                                            "iPhone did not expose a complete CarPlay USB configuration",
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "iPhone USB permission was not granted; tap Reconnect iPhone to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun beginReenumeration(device: UsbDevice) {
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        connectionDiagnostic("USB transition requested count=$reenumerationAttempts")
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested ->
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        phase = Phase.DATAPATHS
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    try {
                        val ncm = openNcm(device)
                        runStack(result.session, ncm)
                    } catch (error: Throwable) {
                        result.session.close()
                        fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail(result.error)
            }
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        var bridgeOwnsConnection = false
        try {
            val layout = IphoneCarPlayConfiguration.readLayout(device, connection)
            val configuration = IphoneCarPlayConfiguration.find(device)
            val configurationId = configuration?.let(com.shilapi.xcertplay.compat.UsbCompat::configurationId)
                ?: layout.configurationId.takeIf { it > 0 }
                ?: throw IphoneUsbException.Protocol(
                    "iPhone exposes no active CarPlay USB configuration (layout=${layout.describe()})",
                )
            // USBMUX is already claimed on another fd. Never reset configuration here:
            // it can invalidate the existing session and kernel interface bookkeeping.
            val activeId = com.shilapi.xcertplay.transport.UsbDeviceLayoutReader.activeConfigurationId(connection)
            com.shilapi.xcertplay.transport.NcmConfigurationGuard.requireActive(activeId, configurationId)
            connectionDiagnostic("NCM configuration confirmed=$activeId action=keep-usbmux-configuration")
            val selectedLayout = IphoneCarPlayConfiguration.readLayout(device, connection)
            val function = if (configuration != null) {
                NcmFunctionDiscovery.find(configuration, selectedLayout)
            } else {
                NcmFunctionDiscovery.find(selectedLayout)
            } ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
            debugLog(
                "ncm config=$configurationId control=${function.control.id}/${function.controlAlternate}" +
                    " data=${function.data.id}/${function.dataAlternate}" +
                    " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                    " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
            )
            return NcmUsbBridge.open(connection, function, ::debugLog).also { bridgeOwnsConnection = true }
        } finally {
            if (!bridgeOwnsConnection) connection.close()
        }
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge) {
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        try {
            if (closed) return
            val mux = Iap2UsbMuxHost.open(usbSession, onDiagnostic = ::connectionDiagnostic)
            this.mux = mux
            debugLog("wired USBMUX host opened")
            val carkit = openCarKitStream(mux)
            debugLog("wired com.apple.carkit.service stream opened")
            // Lab transport diagnostics: packet headers only, never certificate or challenge data.
            fun wireSummary(bytes: ByteArray): String {
                if (bytes.size < 9 || bytes[0].toInt() and 0xff != 0xff ||
                    bytes[1].toInt() and 0xff != 0x5a) return "bytes=${bytes.size}"
                fun value(index: Int) = bytes[index].toInt() and 0xff
                return "bytes=${bytes.size} length=${(value(2) shl 8) or value(3)} " +
                    "flags=${value(4)} seq=${value(5)} ack=${value(6)} session=${value(7)}"
            }
            val tracedCarkit = object : com.shilapi.xcertplay.transport.BlockingDuplexByteStream {
                private val io = ConnectionIoDiagnostics(::connectionDiagnostic)
                override fun send(data: ByteArray) {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        debugLog("wired link TX begin ${wireSummary(data)}")
                        // Bound each TLS write while diagnosing the stalled certificate transfer.
                        for (offset in data.indices step 256) {
                            carkit.send(data.copyOfRange(offset, minOf(offset + 256, data.size)))
                        }
                        debugLog("wired link TX completed bytes=${data.size}")
                        result = ConnectionIoDiagnostics.Result.COMPLETED
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.WRITE, result, elapsedMillis(started))
                    }
                }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        return carkit.recv(maxBytes, timeoutMillis).also { bytes ->
                            result = when {
                                bytes == null -> ConnectionIoDiagnostics.Result.TIMED_OUT
                                bytes.isEmpty() -> ConnectionIoDiagnostics.Result.ENDED
                                else -> ConnectionIoDiagnostics.Result.COMPLETED
                            }
                            if (bytes != null) debugLog("wired link RX ${wireSummary(bytes)}")
                        }
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.READ, result, elapsedMillis(started))
                    }
                }
                override fun close() {
                    try { carkit.close() } finally { io.finish() }
                }
            }
            val csm = Iap2Session.open(
                tracedCarkit,
                traceContext = "wired",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            this.csm = csm
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            if (!attachVpn(ncm, ncmHostMac)) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/VPN AirPlay transport")
            }
            ncmOwnedLocally = false
            debugLog("wired NCM/VPN AirPlay transport attached")
            if (closed) {
                vpnService?.detach()
                return
            }

            val mfi = mfiClient
                ?: throw IphoneUsbException.DeviceUnavailable("本地 MFi 认证未就绪")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = vpnService?.boundPort() ?: airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            debugLog("wired iAP2 control starting")
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = controlLoopTimeoutMillis(),
                locationProvider = locationProvider,
                vehicleStatusProvider = vehicleStatusProvider,
                onIncoming = ::onRouteFrame,
                onProgress = { message -> debugLog("wired $message") },
            )
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.TIMED_OUT -> CarPlayStatus.ControlEnded
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                },
            )
        } catch (error: Throwable) {
            debugLog("wired bring-up failed", error)
            if (!ncmOwnedLocally) vpnService?.detach()
            fail(error)
        } finally {
            if (ncmOwnedLocally) ncm.close()
        }
    }

    private fun openCarKitStream(mux: Iap2UsbMuxHost): BlockingDuplexByteStream {
        onStatus(CarPlayStatus.Pairing)
        val pairingClient = LockdownPairingClient(mux)
        val savedPairRecord = loadPairRecord()
        val pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
        debugLog(
            if (savedPairRecord != null) {
                "wired using saved Lockdown pair record"
            } else {
                "wired created a new Lockdown pair record"
            },
        )
        onStatus(CarPlayStatus.ConnectingControl)
        val carKitClient = LockdownCarKitClient(mux)
        // H52 production path: no phone syslog relay on the live USB transport.
        return try {
            carKitClient.open(pairRecord, config.label)
        } catch (error: Throwable) {
            val rejection = rejectedPairRecordError(error)
            if (savedPairRecord == null || rejection == null) throw error
            debugLog("saved Lockdown pair record rejected by Lockdown error=$rejection; clearing and pairing again")
            clearPairRecord()
            carKitClient.open(pairNewRecord(pairingClient), config.label)
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun rejectedPairRecordError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", ignoreCase = true)) return "InvalidPairRecord"
            if (message.contains("InvalidHostID", ignoreCase = true)) return "InvalidHostID"
            cause = cause.cause
        }
        return null
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        val hotspotMode = config.wirelessHotspotMode
        if (hotspotMode == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(appContext) == false
        ) {
            throw IOException("The car hotspot is off. Turn it on in the car settings and connect again.")
        }
        val manager: WirelessHotspotManager = when (hotspotMode) {
            WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(appContext, ::debugLog)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(appContext, ::debugLog)
            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
                onDiagnostic = ::debugLog,
            )
        }
        hotspot = manager
        val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else {
            HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val bonded = adapter.bondedDevices.orEmpty()
        config.wirelessBluetoothDeviceAddress?.let { selected ->
            return bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }
                ?: throw IOException("The selected iPhone is no longer paired. Choose it again in DiPlay.")
        }
        val iPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true
        }
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        if (connectedIPhones.size > 1) {
            throw IOException(
                "Multiple connected iPhones found: " +
                    connectedIPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" },
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw IOException(
                "Multiple bonded iPhones found and none is currently connected; " +
                    "connect one iPhone and retry",
            )
        }
        if (bonded.size == 1) return bonded.single()
        throw IOException(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
        )
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        // connect 可能阻塞 超时后由调用线程关闭 socket 独立线程避免占用控制线程池
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    /** Reads cached service metadata only; it does not start/cancel discovery or require SCAN. */
    private fun logBluetoothConnectionSnapshot(device: BluetoothDevice, point: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                com.shilapi.xcertplay.compat.ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                connectionDiagnostic("Bluetooth snapshot point=$point unavailable reason=connect-permission")
                return
            }
            val uuids = device.uuids
            val service = UUID.fromString(IAP2_IPHONE_UUID)
            connectionDiagnostic(
                "Bluetooth snapshot point=$point enabled=${bluetoothAdapter?.isEnabled} " +
                    "bondState=${device.bondState} cachedServiceCount=${uuids?.size ?: "unknown"} " +
                    "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}",
            )
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth snapshot point=$point unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    private fun closeWirelessStack(service: CarPlayVpnService? = vpnService) {
        wirelessConnectionProof.clear()
        media.setIapTunnelHandler(null)
        val activeTunnel = wirelessTunnelChannel
        wirelessTunnelChannel = null
        if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

        closeBluetoothBootstrapTransport()

        val activeBonjour = bonjour
        bonjour = null
        if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }

        val activeHotspot = hotspot
        hotspot = null
        if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
        wirelessIdentification = null
        wirelessAirPlayEndpoint = null
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)

        if (service != null) closeBestEffort("AirPlay service") { service.detach() }
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    } catch (_: Exception) {
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        val settingsAddress = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: SecurityException) {
            null
        }
        return listOfNotNull(address, settingsAddress)
            .firstOrNull {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("LocalOnlyHotspot host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        val started = System.nanoTime()
        var completed = false
        try {
            close()
            completed = true
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        } finally {
            connectionDiagnostic("teardown resource=$name completed=$completed elapsedMs=${elapsedMillis(started)}")
        }
    }

    private fun controlLoopTimeoutMillis(): Long = when {
        config.transport == CarPlayTransport.WIRED -> Iap2WiredControlClient.NO_TIMEOUT_MILLIS
        config.locationReportingEnabled -> LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS
        else -> CONTROL_LOOP_TIMEOUT_MILLIS
    }

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            ncm.close()
            return false
        }
        debugLog("wired VPN service bound; attaching NCM transport")
        val result = try {
            service.attach(
                ncm = ncm,
                linkLocal = config.linkLocal,
                hostMac = hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiClient,
                listener = sessionListener,
                media = media,
            )
        } catch (error: Throwable) {
            ncm.close()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            CarPlayVpnService.AttachResult.Started -> {
                debugLog("wired VPN/NCM transport attach result=started")
                true
            }
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                false
            }
            is CarPlayVpnService.AttachResult.Failed -> {
                debugLog("wired VPN/NCM transport attach result=failed ${result.message}")
                ncm.close()
                onStatus(CarPlayStatus.Failed(result.message))
                false
            }
        }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        vpnBound = true
        try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "解除 VPN 服务绑定失败 error=${error.javaClass.simpleName}")
        }
        vpnService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable).forEach {
            try {
                it.close()
            } catch (error: Exception) {
                Log.w(IphoneCarPlayConfiguration.TAG, "注销 iPhone USB 接收器失败 error=${error.javaClass.simpleName}")
            }
        }
        permissionCloseable = null
        attachCloseable = null
    }

    private fun fail(error: Throwable) {
        if (closed) return
        onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName,
            generateSequence(error) { it.cause }.any { it is com.shilapi.xcertplay.network.P2pResetRequiredException }))
    }

    private fun debugLog(message: String) {
        // 界面汇会写一份脱敏日志到 logcat 这里不再重复写 未挂界面时才直接落 logcat
        val listener = uiListener
        if (listener == null) {
            Log.i(IphoneCarPlayConfiguration.TAG, message)
            return
        }
        try {
            listener.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun connectionDiagnostic(message: String) {
        try {
            // The redactor reserves "PHONE " for private phone-side log captures.
            val diagnosticPhase = if (phase == Phase.IPHONE) "USB_DISCOVERY" else phase.name
            debugLog("$CONNECTION_DIAGNOSTIC_PREFIX attempt=$diagnosticAttempt run=${diagnosticRun.get()} phase=$diagnosticPhase $message")
        } catch (_: Exception) {
            // Optional diagnostics must not change transport or shutdown behavior.
        }
    }

    private fun diagnosticFailureClass(error: Throwable): String =
        error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            uiListener?.onDebugLog(
                "$message: ${error.message ?: error.javaClass.simpleName}",
            )
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    private fun onStatus(status: CarPlayStatus) {
        if (closed) return
        mainHandler.post {
            if (!closed && status != lastReportedStatus) {
                lastReportedStatus = status
                connectionDiagnostic("stage=${status.javaClass.simpleName}")
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: 正在加载本地 MFi 认证"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.BluetoothReady -> "STEP bt/ready: Bluetooth byte transport confirmed; opening iAP2 link"
        CarPlayStatus.WirelessIdentified -> "STEP iap2/identified: identification accepted; waiting for authentication"
        CarPlayStatus.WirelessAuthenticated -> "STEP iap2/authenticated: authentication accepted"
        CarPlayStatus.WaitingForWifiJoin -> "STEP wifi/config-sent: configuration sent; Wi-Fi join and AirPlay session not yet confirmed"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        const val CONNECTION_DIAGNOSTIC_PREFIX = "CONNECTION_DIAGNOSTIC"
        private val diagnosticAttempts = AtomicInteger()
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val LOCATION_CONTROL_LOOP_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}
