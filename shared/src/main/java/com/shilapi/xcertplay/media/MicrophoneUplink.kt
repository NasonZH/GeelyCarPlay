package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report = { message ->
        Log.i(TAG, message)
        onDiagnostic(message)
    })
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            running.set(false)
            return false
        }

        val preferredSource = when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        // Vendor ROMs reject VOICE_COMMUNICATION while their own voice assistant owns the mic (this
        // GKUI image records continuously), so fall back to the plain sources before giving up.
        val sources = listOf(
            preferredSource,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
        ).distinct()
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(config.bitrate ?: 48_000).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            stats.failure(MicrophoneFailureStage.ENCODER)
            running.set(false)
            return false
        }
        // Call mode 1 only: hand the input device over before opening it (root preemption).
        if (config.audioType == "telephony") {
            MicOwnerReleaser.preemptIfEnabled(VOICE_CLIENT_PACKAGE)
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        var nextRecorder: AudioRecord? = null
        var chosenSource = preferredSource
        // The GKUI voice assistant records the cabin microphone periodically and holds it
        // exclusively, so a single failed open does not mean "no microphone": retry for a while and
        // grab the input during one of its gaps.
        var attempt = 0
        while (nextRecorder == null && attempt < RECORDER_ATTEMPTS && running.get()) {
            attempt++
            for (source in sources) {
                val candidate = try {
                    if (modernAudioSupported) {
                        ModernAudio.microphoneRecorder(
                            source,
                            AndroidAudioFormat.ENCODING_PCM_16BIT,
                            config.sampleRate,
                            channelMask,
                            bufferSize,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        AudioRecord(
                            source,
                            config.sampleRate,
                            channelMask,
                            AndroidAudioFormat.ENCODING_PCM_16BIT,
                            bufferSize,
                        )
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "microphone recorder source=$source rejected", error)
                    null
                }
                if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                    nextRecorder = candidate
                    chosenSource = source
                    Log.i(TAG, "microphone recorder opened on attempt=$attempt source=$source")
                    break
                }
                runCatching { candidate?.release() }
            }
            if (nextRecorder == null) {
                Log.w(TAG, "microphone sources busy attempt=$attempt/${RECORDER_ATTEMPTS}")
                try {
                    Thread.sleep(RECORDER_RETRY_MILLIS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        val activeRecorder = nextRecorder
        if (activeRecorder == null) {
            Log.w(TAG, "microphone recorder failed to initialize for any source")
            stats.failure(MicrophoneFailureStage.RECORDER_INITIALIZATION, code = AudioRecord.STATE_UNINITIALIZED)
            nextEncoder?.close()
            running.set(false)
            return false
        }
        Log.i(
            TAG,
            "microphone recorder ready source=$chosenSource type=${config.audioType} " +
                "rate=${config.sampleRate} channels=${config.channels} " +
                "frameMs=${config.frameBytes * 1000 / (config.sampleRate * config.channels * 2)}",
        )

        val nextSocket = try {
            com.shilapi.xcertplay.compat.WildcardBind.bind(DatagramSocket(null))
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            stats.failure(MicrophoneFailureStage.SOCKET_CREATION, error)
            nextRecorder?.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = activeRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            if (config.audioType == "telephony") effects = voiceEffects(activeRecorder.audioSessionId)
            activeRecorder.startRecording()
            stats.started(routeType(activeRecorder))
            thread = Thread({ capture(activeRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    private fun voiceEffects(sessionId: Int): List<AudioEffect> = listOfNotNull(
        enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
        },
        enabledEffect("NS") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
        },
    )

    // Advertised effects may still fail to initialize on a vendor ROM. Keep recording without them.
    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(true)
                if (status == AudioEffect.SUCCESS && effect.enabled) {
                    Log.i(TAG, "microphone effect=$name enabled=true")
                    return effect
                }
                Log.w(TAG, "microphone effect=$name could not be enabled status=$status")
            } else {
                Log.i(TAG, "microphone effect=$name unavailable")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseEffect)
        return null
    }

    private fun releaseEffect(effect: AudioEffect) {
        try {
            effect.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect release failed", error)
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        val routeInfo = { routeType(recorder) }
        var filled = 0
        try {
            while (running.get()) {
                stats.reading()
                val count = PortableAudio.readBlocking(recorder, readBuffer, 0, readBuffer.size)
                stats.read(count)
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    return
                }
                if (count == 0) {
                    stats.flush(routeType = routeInfo)
                    continue
                }
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(socket, counters, frame)
                        filled = 0
                    }
                }
                stats.flush(routeType = routeInfo)
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            stats.flush(ended = true, routeType = routeInfo)
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        stats.encoded(bodies.size, if (bodies.isEmpty()) 1 else bodies.count { it.isEmpty() })
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.samplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            stats.sent()
        } catch (error: Exception) {
            stats.sendFailed()
            if (running.get()) throw error
        }
    }

    private fun routeType(recorder: AudioRecord): Int? = PortableAudio.routedDeviceType(recorder)

    override fun close() {
        MicOwnerReleaser.restore(VOICE_CLIENT_PACKAGE)
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach(::releaseEffect)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        // Retry window for a microphone held by the vendor voice assistant: 12 s in 800 ms steps.
        const val RECORDER_ATTEMPTS = 15
        const val RECORDER_RETRY_MILLIS = 800L
        /** Persistent vehicle voice client that owns the cabin microphone on this image. */
        const val VOICE_CLIENT_PACKAGE = "com.iflytek.cutefly.speechclient"
    }
}
