package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import com.shilapi.xcertplay.compat.getOrPutCompat
import com.shilapi.xcertplay.compat.systemService
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** MediaCodec buffer accessors moved from array to indexed methods in API 21. */
private fun inputBufferAt(codec: MediaCodec, index: Int): ByteBuffer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) codec.getInputBuffer(index)
    else codec.inputBuffers?.getOrNull(index)

private fun outputBufferAt(codec: MediaCodec, index: Int): ByteBuffer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) codec.getOutputBuffer(index)
    else codec.outputBuffers?.getOrNull(index)

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val report: (String) -> Unit = {},
    private val separateGeelyFocus: Boolean = false,
) {
    // AudioAttributes/AudioFocusRequest are absent on Android 4.3; keep them as Any and reflect.
    private data class Entry(val channel: AudioChannel, val attributes: Any)
    private data class TrackVolume(val channel: AudioChannel, val streamType: Int, var focusVolume: Float)
    private data class GeelyFocusKey(val channel: AudioChannel, val streamType: Int)
    private class GeelyFocusLease(
        val key: GeelyFocusKey,
        val listener: AudioManager.OnAudioFocusChangeListener,
        val callbackVersion: AtomicInteger = AtomicInteger(),
    )

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: Any? = null
    private var currentLegacyStreamType = AudioManager.STREAM_MUSIC
    private val legacyOnlyTracks = LinkedHashMap<AudioTrack, Pair<AudioChannel, Int>>()
    private var legacyOnlyFocusHeld = false
    private var legacyRequestHeld = false
    private var requestedChannel: AudioChannel? = null
    private val geelyTracks = LinkedHashMap<AudioTrack, GeelyFocusKey>()
    private val geelyListeners = LinkedHashMap<GeelyFocusKey, GeelyFocusLease>()
    private val geelyVolumes = LinkedHashMap<GeelyFocusKey, Float>()
    private val trackVolumes = LinkedHashMap<AudioTrack, TrackVolume>()
    private val playingNavigationTracks = HashSet<AudioTrack>()
    private var focusVolume = FULL_VOLUME
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_GAIN -> setVolume(FULL_VOLUME)
                // Keep CarPlay audio running on permanent or transient loss. Some head units
                // do not send a later gain callback after taking focus back.
            }
        }
    }

    @Synchronized
    fun register(track: AudioTrack, channel: AudioChannel, streamType: Int = AudioManager.STREAM_MUSIC) {
        val volume = if (separateGeelyFocus || channel == AudioChannel.NAVIGATION) FULL_VOLUME else focusVolume
        trackVolumes[track] = TrackVolume(channel, streamType, volume)
        applyTrackVolume(track)
    }

    @Synchronized
    fun navigationStarted(track: AudioTrack) {
        val state = trackVolumes[track] ?: return
        if (state.channel != AudioChannel.NAVIGATION || !playingNavigationTracks.add(track)) return
        updateNavigationVolume()
        if (separateGeelyFocus) acquireGeely(track, state.channel, state.streamType)
    }

    @Synchronized
    fun navigationFinished(track: AudioTrack) {
        if (!playingNavigationTracks.remove(track)) return
        releaseGeelyFocus(track)
        updateNavigationVolume()
    }

    private fun updateNavigationVolume() {
        trackVolumes.filterValues { it.channel == AudioChannel.MEDIA }.keys.forEach(::applyTrackVolume)
        runCatching { report("导航音乐压低: 播放流数=${playingNavigationTracks.size}") }
    }

    private fun applyTrackVolume(track: AudioTrack) {
        val state = trackVolumes[track] ?: return
        val navigationVolume = if (state.channel == AudioChannel.MEDIA && playingNavigationTracks.isNotEmpty()) {
            DUCKED_VOLUME
        } else {
            FULL_VOLUME
        }
        // 焦点和导航使用同一个音量出口 避免重复压低或覆盖通话静音
        PortableAudio.setVolume(track, minOf(state.focusVolume, navigationVolume))
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: Any?, legacyStreamType: Int) {
        if (separateGeelyFocus) {
            acquireGeely(track, channel, legacyStreamType)
            return
        }
        if (!enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        currentLegacyStreamType = legacyStreamType
        if (attributes == null) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
    }

    @Synchronized
    fun acquireLegacy(track: AudioTrack, channel: AudioChannel, streamType: Int) {
        if (separateGeelyFocus) {
            acquireGeely(track, channel, streamType)
            return
        }
        if (!enabled || channel == AudioChannel.NAVIGATION) return
        currentLegacyStreamType = streamType
        legacyOnlyTracks[track] = channel to streamType
        refreshLegacyFocus()
    }

    @Synchronized
    fun release(track: AudioTrack) {
        navigationFinished(track)
        trackVolumes.remove(track)
        releaseGeelyFocus(track)
        if (legacyOnlyTracks.remove(track) != null) refreshLegacyFocus()
        if (active.remove(track) != null) refreshRequest()
    }

    private fun releaseGeelyFocus(track: AudioTrack) {
        val geelyKey = geelyTracks.remove(track)
        if (geelyKey != null && geelyTracks.values.none { it == geelyKey }) {
            geelyVolumes.remove(geelyKey)
            geelyListeners.remove(geelyKey)?.let { lease ->
                runCatching { manager?.abandonAudioFocus(lease.listener) }
                runCatching {
                    report("Audio: Geely focus released channel=${geelyKey.channel} stream=${geelyKey.streamType}")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireGeely(track: AudioTrack, channel: AudioChannel, streamType: Int) {
        if (!enabled) return
        val key = GeelyFocusKey(channel, streamType)
        val oldKey = geelyTracks[track]
        if (oldKey == key) return
        if (oldKey != null) release(track)
        geelyTracks[track] = key
        geelyListeners[key]?.let {
            setGeelyChannelVolume(key, geelyVolumes[key] ?: FULL_VOLUME)
            return
        }
        // A stream is silent until AudioService grants focus. This prevents a rejected vendor
        // stream from continuing over a call, reversing alert, or another system prompt.
        if (!geelyVolumes.containsKey(key)) geelyVolumes[key] = MUTED_VOLUME
        setGeelyChannelVolume(key, geelyVolumes[key] ?: MUTED_VOLUME)
        val service = manager
        if (service == null) {
            runCatching { report("Audio: Geely focus unavailable channel=$channel stream=$streamType; route muted") }
            return
        }
        lateinit var lease: GeelyFocusLease
        val focus = AudioManager.OnAudioFocusChangeListener { change ->
            handleGeelyFocusChange(key, lease, change)
        }
        lease = GeelyFocusLease(key, focus)
        // Install identity before requesting focus: an OEM may dispatch a callback immediately.
        geelyListeners[key] = lease
        val gain = when (channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.NAVIGATION -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.PHONE, AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }
        val versionBeforeRequest = lease.callbackVersion.get()
        val result = runCatching { service.requestAudioFocus(focus, streamType, gain) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Do not overwrite a loss callback that raced with requestAudioFocus's return.
            if (lease.callbackVersion.get() == versionBeforeRequest) setGeelyChannelVolume(key, FULL_VOLUME)
            runCatching {
                report("Audio: Geely focus requested channel=$channel stream=$streamType gain=$gain granted=true")
            }
        } else {
            if (geelyListeners[key] === lease) geelyListeners.remove(key)
            // 车机蓝牙音乐应用会反复抢占焦点 媒体通道不能依赖焦点放行 否则音乐会被永久静音
            val volume = if (key.channel == AudioChannel.MEDIA) FULL_VOLUME else MUTED_VOLUME
            setGeelyChannelVolume(key, volume)
            runCatching {
                val outcome = if (volume == FULL_VOLUME) "media keeps playing" else "route muted"
                report("Audio: Geely focus denied channel=$channel stream=$streamType gain=$gain; $outcome")
            }
        }
    }

    @Synchronized
    private fun handleGeelyFocusChange(
        key: GeelyFocusKey,
        lease: GeelyFocusLease,
        change: Int,
    ) {
        if (geelyListeners[key] !== lease) {
            runCatching {
                report("Audio: ignored stale Geely focus callback channel=${key.channel} stream=${key.streamType} change=$change")
            }
            return
        }
        lease.callbackVersion.incrementAndGet()
        val volume = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> FULL_VOLUME
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> DUCKED_VOLUME
            // 永久丢失焦点时媒体通道保持可听 该车机的蓝牙音乐应用会长期持有焦点
            AudioManager.AUDIOFOCUS_LOSS ->
                if (key.channel == AudioChannel.MEDIA) FULL_VOLUME else MUTED_VOLUME
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> MUTED_VOLUME
            else -> return
        }
        setGeelyChannelVolume(key, volume)
        runCatching {
            report("Audio: Geely focus change channel=${key.channel} stream=${key.streamType} change=$change volume=$volume")
        }
    }

    private fun setGeelyChannelVolume(key: GeelyFocusKey, volume: Float) {
        geelyVolumes[key] = volume
        geelyTracks.filterValues { it == key }.keys.forEach { track ->
            trackVolumes[track]?.focusVolume = volume
            applyTrackVolume(track)
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocusLegacy(streamType: Int): Int = manager
        ?.requestAudioFocus(listener, streamType, AudioManager.AUDIOFOCUS_GAIN)
        ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED

    private fun refreshLegacyFocus() {
        val primary = legacyOnlyTracks.values.maxByOrNull { it.first.focusPriority() }
        if (primary == null) {
            if (legacyOnlyFocusHeld) runCatching { manager?.abandonAudioFocus(listener) }
            legacyOnlyFocusHeld = false
            return
        }
        if (legacyOnlyFocusHeld) runCatching { manager?.abandonAudioFocus(listener) }
        currentLegacyStreamType = primary.second
        legacyOnlyFocusHeld = requestAudioFocusLegacy(primary.second) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val current = request
        if (current == null) {
            if (legacyRequestHeld) {
                @Suppress("DEPRECATION")
                runCatching { manager?.abandonAudioFocus(listener) }
            }
            legacyRequestHeld = false
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                manager?.javaClass?.getMethod(
                    "abandonAudioFocusRequest", Class.forName("android.media.AudioFocusRequest"),
                )?.invoke(manager, current)
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager?.abandonAudioFocus(listener) }
        }
        request = null
        legacyRequestHeld = false
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            abandonFocus()
            requestedChannel = null
            return
        }
        if (request != null && requestedChannel == primary.channel) return
        abandonFocus()
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            val streamType = if (modernAudioSupported) ModernAudio.legacyStreamType(primary.attributes)
            else currentLegacyStreamType
            legacyRequestHeld = requestAudioFocusLegacy(streamType) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return
        }
        val builderClass = Class.forName("android.media.AudioFocusRequest\$Builder")
        val builder = builderClass.getConstructor(Integer.TYPE).newInstance(gain)
        builderClass.getMethod("setAudioAttributes", Class.forName("android.media.AudioAttributes"))
            .invoke(builder, primary.attributes)
        builderClass.getMethod(
            "setOnAudioFocusChangeListener", AudioManager.OnAudioFocusChangeListener::class.java,
            android.os.Handler::class.java,
        ).invoke(builder, listener, android.os.Handler(android.os.Looper.getMainLooper()))
        val next = builderClass.getMethod("build").invoke(builder)
        request = next
        requestedChannel = primary.channel
        val result = runCatching {
            manager?.javaClass?.getMethod(
                "requestAudioFocus", Class.forName("android.media.AudioFocusRequest"),
            )?.invoke(manager, next) as? Int
        }.getOrNull()
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setVolume(volume: Float) {
        focusVolume = volume
        trackVolumes.filterValues { it.channel != AudioChannel.NAVIGATION }.forEach { (track, state) ->
            state.focusVolume = volume
            applyTrackVolume(track)
        }
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
        const val MUTED_VOLUME = 0f
    }
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    geelyAudioRouting: Boolean = false,
    private val geelyNavigationAlert: Boolean = false,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    private val onVideoSurfaceFailure: (Surface, String) -> Unit = { _, _ -> },
    private val onVideoSurfaceFrameRendered: (Surface) -> Unit = {},
) : MediaSink {
    @Volatile private var closed = false
    private val appContext = context?.applicationContext
    private val audioManager = appContext?.systemService(AudioManager::class.java, "audio")
    private val geelyAudio = if (geelyAudioRouting) GeelyAudioCapabilities.detect() else null
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled || geelyAudio != null,
        onAudioDiagnostic,
        separateGeelyFocus = geelyAudio != null,
    )
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    /** 最近一次收到视频帧的时间 供链路看门狗判断数据流是否停止 */
    @Volatile var lastVideoFrameAt = 0L
        private set
    /** 主画面首个视频参数到达时间 自动进入投屏前用它确认视频流已开始协商 */
    @Volatile var firstVideoConfigAt = 0L
        private set
    /** 主画面首个视频帧到达时间 仅用于连接时间线 */
    @Volatile var firstVideoFrameAt = 0L
        private set
    private val videoSurfaceLock = Any()
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        synchronized(videoSurfaceLock) {
            if (!closed) videoRecoveryHandlers[type] = handler
        }
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        synchronized(videoSurfaceLock) {
            if (!closed) videoDiagnosticHandlers[type] = handler
        }
    }

    private fun requestVideoRecovery(type: Int) {
        if (closed || !recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { if (!closed) videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        synchronized(videoSurfaceLock) {
            if (closed) return
            surfaces[type] = surface
            videoDecoders[type]?.setSurface(surface)
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        val decoder = synchronized(videoSurfaceLock) {
            if (surfaces.remove(type, surface)) videoDecoders[type] else null
        }
        decoder?.detachSurfaceAndWait(surface)
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        val id = type to key
        synchronized(mirrorLock) {
            if (closed) return
            mirrorDecoders.remove(id)?.close()
            if (surface == null) {
                mirrorSurfaces.remove(id)
                return
            }
            mirrorSurfaces[id] = surface
        }
        lastVideoConfig[type]?.let { (codec, data) -> mirrorDecoders(type).forEach { it.configure(codec, data) } }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(mirrorLock) {
        if (closed || mirrorSurfaces.isEmpty()) return emptyList()
        mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
            mirrorDecoders.getOrPut(id) { newVideoDecoder(type, surface, " stream=$type mirror=${id.second}") }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            if (closed) return
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        synchronized(videoSurfaceLock) {
            if (!closed) pendingVideoCodec[type] = codec
        }
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = synchronized(videoSurfaceLock) {
            val decoder = videoDecoder(type) ?: return
            if (type == 110 && firstVideoConfigAt == 0L) firstVideoConfigAt = SystemClock.elapsedRealtime()
            val codec = pendingVideoCodec[type] ?: VideoCodec.H264
            lastVideoConfig[type] = codec to codecData
            decoder.configure(codec, codecData)
            codec
        }
        mirrorDecoders(type).forEach { it.configure(codec, codecData) }
    }

    override fun onVideoFrame(type: Int, frame: VideoFrame) {
        try {
            val decoder = videoDecoder(type) ?: return
            val now = SystemClock.elapsedRealtime()
            lastVideoFrameAt = now
            if (type == 110 && firstVideoFrameAt == 0L) firstVideoFrameAt = now
            decoder.submit(frame)
            mirrorDecoders(type).forEach { it.submit(frame) }
        } finally {
            frame.release()
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (closed) return
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            val decoder = videoDecoders[type]
            decoder?.closeAndWait()
            if (decoder != null) videoDecoders.remove(type, decoder)
            synchronized(mirrorLock) {
                mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
            }
            lastVideoConfig.remove(type)
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (closed) return
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        val renderer = audioRenderer(id, format) ?: return
        renderer.start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format)?.submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        audioRenderers.remove(id)?.close()
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            if (closed && active) return
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after && (!after || !closed)) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        // This callback runs on the downlink thread. Opening the microphone retries for up to ~12 s
        // while the vehicle voice client owns the input, so it must never block playback: the
        // downlink would produce no PCM at all for the whole call (observed on the H52).
        val uplink = microphoneUplinks.getOrPutCompat(id) { MicrophoneUplink(config, onAudioDiagnostic) }
        Thread({
            try {
                if (config.audioType == "telephony") enterCommunicationMode(id)
                if (!uplink.start()) {
                    microphoneUplinks.remove(id, uplink)
                    restoreAudioMode(id)
                }
            } catch (error: Exception) {
                Log.e("xcertplay-usb", "microphone start failed stream=$id", error)
                MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
                onMicrophoneStopped(id)
            }
        }, "carplay-mic-open").apply { isDaemon = true }.start()
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        try {
            microphoneUplinks.remove(id)?.close()
        } finally {
            restoreAudioMode(id)
        }
    }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
            savedAudioMode = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationModeStream = id
            Log.i("xcertplay-usb", "audio mode $savedAudioMode -> ${manager.mode} for telephony stream=$id")
        }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            communicationModeStream = null
            try {
                manager.mode = savedAudioMode
                Log.i("xcertplay-usb", "audio mode restored to ${manager.mode}")
            } catch (error: RuntimeException) {
                Log.w("xcertplay-usb", "could not restore audio mode $savedAudioMode", error)
            }
        }
    }

    /** 整组关闭会等待解码和音频线程退出 仅在后台线程调用 */
    fun close() {
        val audio = synchronized(this) {
            if (closed) return
            closed = true
            audioRenderers.values.toList().also { audioRenderers.clear() }
        }
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        audio.forEach(AudioRenderer::close)
        val videos = synchronized(videoSurfaceLock) { videoDecoders.values.toList() }
        videos.forEach(VideoDecoder::closeAndWait)
        synchronized(videoSurfaceLock) {
            videoDecoders.clear()
            surfaces.clear()
            pendingVideoCodec.clear()
            lastVideoConfig.clear()
            videoRecoveryHandlers.clear()
            videoDiagnosticHandlers.clear()
        }
        val mirrors = synchronized(mirrorLock) {
            val decoders = mirrorDecoders.values.toList()
            mirrorDecoders.clear()
            mirrorSurfaces.clear()
            decoders
        }
        mirrors.forEach(VideoDecoder::closeAndWait)
        recoveryExecutor.shutdownNow()
        recoveryPending.set(false)
        audio.forEach(AudioRenderer::closeAndWait)
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        try {
            microphoneUplinks.values.forEach(MicrophoneUplink::close)
        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
    }

    private fun videoDecoder(type: Int): VideoDecoder? = synchronized(videoSurfaceLock) {
        if (closed) return@synchronized null
        videoDecoders.getOrPutCompat(type) { newVideoDecoder(type, surfaces[type] ?: defaultSurface) }
    }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null) = VideoDecoder(
        type,
        surface,
        videoWidth,
        videoHeight,
        preferSoftwareHevcDecoder,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        frameRendered = { renderedSurface ->
            if (type == 110 && statsLabel == null) onVideoSurfaceFrameRendered(renderedSurface)
        },
        surfaceFailure = { failedSurface, reason ->
            if (type == 110 && statsLabel == null) onVideoSurfaceFailure(failedSurface, reason)
        },
        statsLabel = statsLabel,
    )

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer? {
        if (closed) return null
        val renderFormat = opusOutputFormat(format)
        val existing = audioRenderers[id]
        if (existing?.format == renderFormat) return existing
        existing?.close()
        return AudioRenderer(
            renderFormat,
            advancedAudioChannelMapping,
            audioFocusEnabled || geelyAudio != null,
            mediaChannel,
            navigationChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            geelyAudio,
            geelyNavigationAlert,
        ).also { audioRenderers[id] = it }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    private val surfaceFailure: (Surface, String) -> Unit,
    private val frameRendered: (Surface) -> Unit,
    statsLabel: String? = null,
) : Closeable {
    private val queue = VideoDecodeQueue()
    private val lifecycleLock = Any()
    private val stopped = CountDownLatch(1)
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == 110) "" else " stream=$streamType")
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(frame: VideoFrame) {
        frame.retain()
        stats.onReceived(frame.length)
        queue.offer(VideoJob.Frame(frame))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    fun detachSurfaceAndWait(surface: Surface) {
        val completed = CountDownLatch(1)
        val signal = synchronized(lifecycleLock) {
            if (!running) stopped else {
                queue.discardFrames()
                queue.offer(VideoJob.DetachSurface(surface, completed))
                completed
            }
        }
        awaitCompletion(signal)
    }

    fun closeAndWait() {
        close()
        awaitCompletion(stopped)
    }

    private fun awaitCompletion(signal: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                signal.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    override fun close() {
        running = false
        queue.stopAcceptingFrames()
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            try {
                                if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
                                    queue.discardFrames()
                                    recover("video backlog exceeded 250 ms")
                                } else {
                                    feed(job.frame)
                                }
                            } finally {
                                job.frame.release()
                            }
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.DetachSurface -> {
                            try {
                                if (outputSurface === job.surface) changeSurface(null)
                            } finally {
                                job.completed.countDown()
                            }
                        }
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) report("decoder error ${error.javaClass.simpleName}; waiting for keyframe")
                    releaseDecoder()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            try {
                releaseDecoder()
            } finally {
                synchronized(lifecycleLock) {
                    running = false
                    queue.stopAcceptingFrames()
                    queue.completeSurfaceDetaches()
                    stopped.countDown()
                }
            }
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        val attempts = listOf(
            DecoderAttempt(codecName = null, tuned = true),
            DecoderAttempt(codecName = null, tuned = false),
        ) + softwareDecoderName(mime)?.let { listOf(DecoderAttempt(it, tuned = false)) }.orEmpty()
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
            surfaceFailure(surface, "解码器无法配置当前视频输出 mime=$mime size=${width}x$height")
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${next.name} mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    private fun softwareDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        renderedFrameLogged = false
        lastKeyFrameRequestNs = 0L
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                requestKeyFrameIfDue()
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
        requestKeyFrameIfDue()
    }

    private fun feed(frame: VideoFrame) {
        val bytes = frame.bytes
        val length = MediaCodecSupport.toAnnexB(bytes, frame.length)
        val config = lastConfig
        if (length <= 0) {
            recover("invalid video access unit")
            return
        }
        if (config == null || outputSurface == null) {
            return
        }
        if (!referenceChain.accepts(bytes, length, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder
        if (codec == null) {
            return
        }
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${frame.length} annexB=$length " +
                    "head=${bytes.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) {
            recover("video decoder input stalled")
            return
        }
        val input = inputBufferAt(codec, index)
        if (input == null) {
            recover("video decoder input buffer unavailable")
            return
        }
        input.clear()
        if (length <= input.remaining()) {
            input.put(bytes, 0, length)
            codec.queueInputBuffer(index, 0, length, System.nanoTime() / 1000, 0)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                index >= 0 -> {
                    val render = outputSurface != null
                    codec.releaseOutputBuffer(index, render)
                    if (render) stats.onRendered()
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        outputSurface?.let(frameRendered)
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_FRAME_AGE_NS = 250_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val geelyAudio: GeelyAudioCapabilities?,
    private val geelyNavigationAlert: Boolean,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    // AudioAttributes is API 21; an Any field keeps the Android 4.3 verifier away from it.
    private var trackAttributes: Any? = null
    private var legacyTrackStreamType = AudioManager.STREAM_MUSIC
    private var mappedChannel: AudioChannel? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    private val lifecycleLock = Any()
    private val stopped = CountDownLatch(1)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    // Used only when the firmware has no audio/opus MediaCodec (see OpusVoiceDecoder).
    private var opusDecoder: OpusVoiceDecoder? = null
    private var opusPcm = ByteArray(0)
    /** Absolute peak of the decoded PCM since the last stats line; 0 means true silence. */
    private var pcmPeak = 0
    /** RTP 时间戳前向跳变统计 只在 AAC 下按 1024 采样帧折算 */
    private var expectedSampleTimestamp = 0L
    private var gapEvents = 0
    private var gapFrames = 0L
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    /** Reused across drain calls: one BufferInfo per renderer instead of one per packet. */
    private val decoderBufferInfo = MediaCodec.BufferInfo()
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var opusDecodeFailureLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    /** Prebuffer write granularity: 20 ms of PCM, so a chunk fits even the smallest granted track. */
    private val prebufferChunkBytes = format.sampleRate / 50 * frameBytes
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var decodedFramesThisWindow = 0L
    private val trimEvents = java.util.concurrent.atomic.AtomicInteger()
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() = synchronized(lifecycleLock) {
        if (!running || started) return@synchronized
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) {
                // AtomicLong.accumulateAndGet is API 24; use compareAndSet on older Android.
                val gap = (now - previous) / 1_000_000L
                var current = maxArrivalGapMs.get()
                while (gap > current && !maxArrivalGapMs.compareAndSet(current, gap)) {
                    current = maxArrivalGapMs.get()
                }
            }
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
            return
        }
        // Bound latency in wall-clock terms: a decode that falls behind must drop the oldest audio
        // instead of letting the backlog stretch playback, which is what made navigation distort
        // after a minute on the head unit.
        // Hysteresis: trim rarely and deeply instead of peeling one packet per arrival, so a slow
        // decode produces one short deliberate resync instead of continuous skipping.
        // Music keeps the user's large jitter buffer: Wi-Fi gaps of several hundred ms are normal
        // there, and trimming it turned steady playback into drop-outs. Calls and prompts stay
        // low-latency, which is what this cap is for.
        if (format.audioType != "media" && queue.size > MAX_LIVE_QUEUED_PACKETS) {
            var trimmed = 0
            while (queue.size > TRIM_TARGET_PACKETS) {
                if (queue.poll() == null) break
                trimmed++
            }
            if (trimmed > 0) {
                packetsDropped.addAndGet(trimmed)
                trimEvents.incrementAndGet()
                // Drop the backlog only. Resetting the decoder here used to flush it and write a
                // 20 ms silence frame; at several trims per second that hole is the audible noise
                // on navigation and call audio. The decoder now continues from its own state.
            }
        }
    }

    override fun close() = synchronized(lifecycleLock) {
        if (!running) return@synchronized
        running = false
        if (started) thread.interrupt() else stopped.countDown()
    }

    fun closeAndWait() {
        close()
        try {
            if (!stopped.await(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "等待音频线程退出超时 audioType=${format.audioType}")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun run() {
        try {
            if (!running) return
            // 音频解码需要更高调度优先级 避免接收队列因视频负载而积压
            val priority = runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                Process.getThreadPriority(Process.myTid())
            }.getOrDefault(Int.MIN_VALUE)
            report("Audio: thread priority=$priority audioType=${format.audioType}")
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> {
                    configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                    if (codec == null) {
                        opusDecoder = openOpusVoiceDecoder(format.sampleRate, format.channels)
                        report(
                            "Audio: no platform Opus decoder; backend=${opusDecoder?.backend ?: "none"} " +
                                "audioType=${format.audioType}",
                        )
                    }
                }
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            requestAudioFocus()
            while (running) {
                val pollMillis = if (playbackStarted) AUDIO_POLL_MILLIS else AUDIO_IDLE_POLL_MILLIS
                queue.poll(pollMillis, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                codec?.let(::drainCodec)
                maintainPlaybackBuffer()
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                report("Audio: renderer failed audioType=${format.audioType} error=${error.javaClass.simpleName}")
            }
        } finally {
            running = false
            try {
                runCatching { logStatsIfDue(force = true) }
                release()
            } finally {
                stopped.countDown()
            }
        }
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()} " +
                    "inputPacking=direct-adts outputOwnership=release-before-write outputInfo=reused",
            )
        }
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(mediaFormat, null, null, 0)
                it.start()
                Log.i(TAG, "audio decoder configured mime=$mime name=${it.name}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel, format.audioType)
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        legacyTrackStreamType = AudioStreamRouting.legacyStreamType(selection.channel, streamOverride)
        // API 18 has no usage-based attributes, and STREAM_VOICE_CALL stays inaudible on this unit
        // unless the head unit itself is in a call: keep CarPlay call audio on the media path, which
        // is the routing that was verified end-to-end (H52 channel 23 still wins through
        // streamOverride when the factory route is enabled).
        if (!modernAudioSupported && selection.channel == AudioChannel.PHONE &&
            streamOverride == null
        ) {
            legacyTrackStreamType = AudioManager.STREAM_MUSIC
        }
        trackAttributes = if (modernAudioSupported) {
            ModernAudio.attributes(usageFor(selection.channel), contentTypeFor(selection.contentType), streamOverride)
        } else {
            null
        }
        val built: AudioTrack
        var routeLabel: String
        if (!modernAudioSupported) {
            // API 18 only supports the legacy stream-type AudioTrack constructor.
            @Suppress("DEPRECATION")
            fun legacyTrack() = AudioTrack(legacyTrackStreamType, format.sampleRate, channelMask,
                encoding, plan.trackBufferBytes, AudioTrack.MODE_STREAM)
            built = if (geelyAudio == null) legacyTrack() else LegacyAudioFallback.build(
                createLegacy = { legacyTrack() },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    report("Audio: H52 stream=$legacyTrackStreamType unavailable; falling back to MUSIC=3")
                    legacyTrackStreamType = AudioManager.STREAM_MUSIC
                    legacyTrack()
                },
            )
            routeLabel = "streamType=$legacyTrackStreamType(legacy)"
        } else if (streamOverride == null) {
            routeLabel = "usage"
            built = buildUsageTrack(selection, encoding, channelMask, plan.trackBufferBytes)
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    @Suppress("DEPRECATION")
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    buildUsageTrack(selection, encoding, channelMask, plan.trackBufferBytes)
                },
            )
        }
        track = built
        audioFocusCoordinator.register(built, selection.channel, legacyTrackStreamType)
        val capacityBytes = (PortableAudio.bufferSizeInFrames(built).takeIf { it > 0 }?.times(frameBytes)
            ?: PortableAudio.bufferSizeBytes(built)).coerceAtLeast(minBuffer)
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, prebufferChunkBytes)
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond}")
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled || geelyAudio != null) "on" else "off"}",
        )
    }

    /** Null selects usage routing; a stream number, including 0, is an explicit legacy route. */
    private fun channelOverride(channel: AudioChannel, audioType: String): Int? = AudioStreamRouting.override(
        channel = channel,
        audioType = audioType,
        geelyAudio = geelyAudio,
        separateGeelyAlerts = geelyNavigationAlert,
        mediaChannel = mediaChannel,
        navigationChannel = navigationChannel,
    )

    @RequiresApi(Build.VERSION_CODES.M)
    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int?,
    ): Any = ModernAudio.attributes(usageFor(selection.channel), contentTypeFor(selection.contentType), streamOverride)

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun buildUsageTrack(
        selection: AudioChannelSelection,
        encoding: Int,
        channelMask: Int,
        bufferBytes: Int,
    ): AudioTrack = ModernAudio.usageTrack(
        attributes = audioAttributesFor(selection, null),
        encoding = encoding,
        sampleRate = format.sampleRate,
        channelMask = channelMask,
        bufferBytes = bufferBytes,
    )

    /** 导航焦点由实际播放状态管理 其他音频沿用原有焦点申请 */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "导航焦点与音乐压低按播放状态控制")
            return
        }
        val currentTrack = track ?: return
        val attributes = trackAttributes
        if (attributes == null && !modernAudioSupported) {
            audioFocusCoordinator.acquireLegacy(currentTrack, channel, legacyTrackStreamType)
        } else {
            audioFocusCoordinator.acquire(currentTrack, channel, attributes, legacyTrackStreamType)
        }
    }

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> 1 // USAGE_MEDIA
        AudioChannel.PHONE -> 2 // USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> 16 // USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> 12 // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> 2 // CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> 1 // CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        trackSampleGap(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val offset = 12
                val length = rtp.size - offset
                if (length > 0) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=$length " +
                                "head=${rtp.copyOfRange(offset, offset + minOf(length, 16)).toHexString()}",
                        )
                    }
                    if (!feedAacDirect(rtp, offset, length, timestampUs)) {
                        val accessUnit = rtp.copyOfRange(offset, rtp.size)
                        feedCodec(
                            MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                            timestampUs,
                        )
                    }
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                val decoder = opusDecoder
                if (decoder != null) {
                    val frames = decoder.decode(accessUnit)
                    if (frames > 0) {
                        decodedFramesThisWindow += frames.toLong()
                        val bytes = interleavePcm16(decoder.samples(), frames)
                        if (bytes > 0) writePcm(opusPcm, 0, bytes)
                    } else if (!opusDecodeFailureLogged) {
                        opusDecodeFailureLogged = true
                        Log.w(TAG, "Opus decoded no frames backend=${decoder.backend} bytes=${accessUnit.size}")
                    }
                } else {
                    feedCodec(accessUnit, timestampUs)
                }
            }
        }
    }

    /**
     * Packs decoded Opus samples into [opusPcm] as little-endian 16-bit PCM and returns the byte
     * count to write. The buffer is reused, so [writePcm] must be given the returned length.
     */
    private fun interleavePcm16(samples: ShortArray, frames: Int): Int {
        val values = frames * format.channels
        val bytes = values * 2
        if (opusPcm.size < bytes) opusPcm = ByteArray(bytes)
        var offset = 0
        var peak = 0
        for (index in 0 until values) {
            val value = samples[index].toInt()
            val magnitude = if (value < 0) -value else value
            if (magnitude > peak) peak = magnitude
            opusPcm[offset++] = (value and 0xFF).toByte()
            opusPcm[offset++] = ((value shr 8) and 0xFF).toByte()
        }
        if (peak > pcmPeak) pcmPeak = peak
        return bytes
    }

    /** RTP timestamps for Opus always run on a 48 kHz clock, independent of the decode rate. */
    private val timestampRate = if (format.codec == AudioCodecKind.OPUS) 48_000 else format.sampleRate

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / timestampRate

    /** 记录 RTP 时间戳前向跳变 用于统计丢包造成的音频缺口 */
    private fun trackSampleGap(sample: Int) {
        if (format.codec != AudioCodecKind.AAC_LC) return
        val current = sample.toLong() and 0xffff_ffffL
        val expected = expectedSampleTimestamp
        if (expected != 0L) {
            val delta = (current - expected) and 0xffff_ffffL
            if (delta > 0 && delta < MAX_GAP_SAMPLES) {
                gapEvents++
                gapFrames += delta / AAC_FRAME_SAMPLES
            }
        }
        expectedSampleTimestamp = (current + AAC_FRAME_SAMPLES) and 0xffff_ffffL
    }

    /** Writes an ADTS frame straight into the codec input buffer; false means "codec not ready". */
    private fun feedAacDirect(
        payload: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
    ): Boolean {
        val active = codec ?: return false
        val index = active.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            return true
        }
        val input = inputBufferAt(active, index) ?: return false
        input.clear()
        val written = MediaCodecSupport.writeAdtsFrame(
            input, payload, offset, length, format.sampleRate, format.channels,
        )
        if (written < 0) {
            active.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
            return true
        }
        active.queueInputBuffer(index, 0, written, presentationTimeUs, 0)
        inputQueued++
        drainCodec(active)
        return true
    }
    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = inputBufferAt(codec, index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        // Reused BufferInfo plus "release before write": the codec buffer is handed back to the
        // decoder before AudioTrack.write can block, so a slow sink never starves the decoder.
        val info = decoderBufferInfo
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = codec.outputFormat
                    val sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    report("Audio: decoder output codec=${format.codec} rate=$sampleRate channels=$channels " +
                        "trackRate=${format.sampleRate} trackChannels=${format.channels}")
                    if (format.codec == AudioCodecKind.OPUS) {
                        check(sampleRate == format.sampleRate && channels == format.channels) {
                            "Opus 解码输出与播放格式不一致"
                        }
                    }
                }
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    var copied = false
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                        val output = outputBufferAt(codec, index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            copied = true
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (copied) {
                        writePcm(pcm, 0, size)
                        decodedFramesThisWindow += (size / (2 * format.channels.coerceAtLeast(1))).toLong()
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        if (length > 0 && playbackStarted) audioFocusCoordinator.navigationStarted(track)
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, prebufferChunkBytes)
            }
            val writeStarted = System.nanoTime()
            val count = PortableAudio.writeBlocking(track, data, offset + written, writeLength)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            written += count
            val framesWritten = count / frameBytes
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        audioFocusCoordinator.navigationStarted(track)
        underrunsAtPlaybackStart = PortableAudio.underrunCount(track)
        track.play()
        playbackStarted = true
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                PortableAudio.underrunCount(track) > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition)) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            prebufferBytes = 0
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
        if (mappedChannel == AudioChannel.NAVIGATION && playbackStarted && queue.isEmpty() &&
            System.nanoTime() - maxOf(lastPcmWriteNs, lastArrivalNs.get()) >= NAVIGATION_RELEASE_DELAY_NS &&
            bufferProgress.queuedBytes(track.playbackHeadPosition) == 0L
        ) {
            audioFocusCoordinator.navigationFinished(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.let { PortableAudio.underrunCount(it) } ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=${currentTrack?.let { PortableAudio.routedDeviceType(it) } ?: -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${currentTrack?.let { PortableAudio.bufferSizeInFrames(it) } ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "decodeDeficit=${decodedFramesThisWindow - writtenFramesThisWindow} trims=${trimEvents.getAndSet(0)} " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow peak=$pcmPeak " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount " +
            "gaps=$gapEvents gapFrames=$gapFrames ended=$force"
        report(line)
        pcmPeak = 0
        gapEvents = 0
        gapFrames = 0L
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        decodedFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        val decoder = opusDecoder
        opusDecoder = null
        decoder?.close()
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            audioFocusCoordinator.release(track)
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val CLOSE_TIMEOUT_MILLIS = 2_000L
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 20L
        // 未起播时降低轮询频率 减少空闲唤醒
        const val AUDIO_IDLE_POLL_MILLIS = 40L
        const val AAC_FRAME_SAMPLES = 1_024L
        const val MAX_GAP_SAMPLES = 96_000L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        const val NAVIGATION_RELEASE_DELAY_NS = 250_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        // Real-time cap (12 x 20 ms = 240 ms): older packets are dropped instead of stretching audio.
        const val MAX_LIVE_QUEUED_PACKETS = 12
        /** After a trim the queue is cut to this many packets (120 ms) to give headroom again. */
        const val TRIM_TARGET_PACKETS = 6
        const val STATS_WINDOW_NS = 5_000_000_000L
        // Periodic audio stats (every 5s) already carry the decode health; the
        // per-buffer line was 50x more often and only added noise to the head-unit log.
        const val DECODED_BUFFER_LOG_INTERVAL = 600
    }
}
