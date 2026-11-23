package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.Closeable

/**
 * Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay
 * microphone uplink.
 */
internal class OpusEncoder(bitrate: Int) : Closeable {
    /**
     * Bundled libopus first: the GEELY image has no audio/opus encoder, and libopus behaves the same
     * on every head unit instead of depending on vendor codecs.
     */
    private var nativeEncoder = if (NativeOpus.isAvailable) {
        NativeOpus.nativeEncoderCreate(
            SAMPLE_RATE,
            CHANNELS,
            bitrate.coerceIn(MIN_BITRATE, MAX_BITRATE),
            COMPLEXITY,
        ).also { handle ->
            if (handle != 0L) Log.i(TAG, "Opus microphone native encoder started bitrate=$bitrate")
        }
    } else {
        0L
    }

    private val codec: MediaCodec? = if (nativeEncoder != 0L) null else try {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            SAMPLE_RATE,
            CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also {
            it.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            it.start()
            Log.i(TAG, "Opus microphone encoder started bitrate=$bitrate")
        }
    } catch (error: Exception) {
        Log.w(TAG, "Opus microphone encoder unavailable", error)
        null
    }
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L
    private var closed = false
    private var outputPackets = 0

    /**
     * Pure-Java fallback. The GEELY image has no audio/opus *encoder* either, and without it the
     * CarPlay microphone uplink is silent for the other party while downlink works fine.
     */
    private val software: io.github.jaredmdobson.concentus.OpusEncoder? =
        if (nativeEncoder == 0L && codec == null) runCatching {
            io.github.jaredmdobson.concentus.OpusEncoder(
                SAMPLE_RATE,
                CHANNELS,
                io.github.jaredmdobson.concentus.OpusApplication.OPUS_APPLICATION_VOIP,
            ).apply {
                setBitrate(bitrate.coerceIn(6_000, 40_000))
                setComplexity(5)
                setUseVBR(true)
                setUseDTX(false)
                setUseInbandFEC(false)
            }.also { Log.i(TAG, "Opus microphone software encoder started bitrate=$bitrate") }
        }.onFailure {
            Log.w(TAG, "software Opus microphone encoder unavailable", it)
        }.getOrNull() else null
    private val pcmSamples = ShortArray(SAMPLES_PER_FRAME)
    private val encodedPcm = ByteArray(MAX_INPUT_BYTES)

    val available: Boolean get() = (nativeEncoder != 0L || codec != null || software != null) && !closed

    /**
     * Queues one 20 ms PCM frame and returns all Opus access units made available by the codec.
     */
    fun encode(pcm: ByteArray): List<ByteArray> {
        if (closed) return emptyList()
        if (nativeEncoder != 0L) return encodeNative(pcm)
        if (codec == null) {
            val software = software ?: return emptyList()
            return encodeSoftware(software, pcm)
        }
        val codec = codec ?: return emptyList()
        val inputIndex = try {
            codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (error: Exception) {
            Log.w(TAG, "Opus microphone input dequeue failed", error)
            return emptyList()
        }
        if (inputIndex >= 0) {
            val input = inputBufferAt(codec, inputIndex)
            if (input == null || pcm.size > input.remaining()) {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            } else {
                input.clear()
                input.put(pcm)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    pcm.size,
                    presentationTimeUs,
                    0,
                )
                presentationTimeUs += INPUT_DURATION_US
            }
        }
        return drain()
    }

    private fun drain(): List<ByteArray> {
        val codec = codec ?: return emptyList()
        val output = ArrayList<ByteArray>()
        while (!closed) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (error: Exception) {
                Log.w(TAG, "Opus microphone output dequeue failed", error)
                return output
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return output
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    val buffer = outputBufferAt(codec, index)
                    if (buffer != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(bytes)
                        output.add(bytes)
                        outputPackets++
                        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
                            Log.i(
                                TAG,
                                "Opus microphone packet=$outputPackets bytes=${bytes.size} " +
                                    "head=${bytes.copyOf(minOf(bytes.size, 16)).toHexString()}",
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        return output
    }

    /** Encodes one 20 ms frame with the bundled libopus. */
    private fun encodeNative(pcm: ByteArray): List<ByteArray> {
        val handle = nativeEncoder
        if (handle == 0L) return emptyList()
        val samples = toSamples(pcm)
        if (samples <= 0) return emptyList()
        val bytes = NativeOpus.nativeEncode(handle, pcmSamples, 0, samples, encodedPcm, 0, encodedPcm.size)
        if (bytes <= 0) return emptyList()
        outputPackets++
        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
            Log.i(
                TAG,
                "Opus microphone native packet=$outputPackets bytes=$bytes " +
                    "head=${encodedPcm.copyOf(minOf(bytes, 16)).toHexString()}",
            )
        }
        return listOf(encodedPcm.copyOf(bytes))
    }

    /** Encodes one 20 ms frame with the Concentus fallback. */
    private fun encodeSoftware(
        encoder: io.github.jaredmdobson.concentus.OpusEncoder,
        pcm: ByteArray,
    ): List<ByteArray> {
        val samples = toSamples(pcm)
        if (samples <= 0) return emptyList()
        val bytes = runCatching {
            encoder.encode(pcmSamples, 0, samples, encodedPcm, 0, encodedPcm.size)
        }.getOrNull() ?: return emptyList()
        if (bytes <= 0) return emptyList()
        outputPackets++
        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
            Log.i(
                TAG,
                "Opus microphone software packet=$outputPackets bytes=$bytes " +
                    "head=${encodedPcm.copyOf(minOf(bytes, 16)).toHexString()}",
            )
        }
        return listOf(encodedPcm.copyOf(bytes))
    }

    /** Converts little-endian 16-bit PCM bytes into [pcmSamples] and returns the sample count. */
    private fun toSamples(pcm: ByteArray): Int {
        val samples = (pcm.size / 2).coerceAtMost(pcmSamples.size)
        for (index in 0 until samples) {
            val low = pcm[index * 2].toInt() and 0xFF
            val high = pcm[index * 2 + 1].toInt()
            pcmSamples[index] = (low or (high shl 8)).toShort()
        }
        return samples
    }

    override fun close() {
        if (closed) return
        closed = true
        val native = nativeEncoder
        nativeEncoder = 0L
        if (native != 0L) NativeOpus.nativeEncoderDestroy(native)
        val codec = codec ?: return
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

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val INPUT_TIMEOUT_US = 10_000L
        const val INPUT_DURATION_US = 20_000L
        const val MAX_INPUT_BYTES = 4_096
        const val MIN_BITRATE = 6_000
        const val MAX_BITRATE = 96_000
        const val COMPLEXITY = 5
        const val FIRST_PACKET_LOG_COUNT = 3
        // 20 ms at 48 kHz, the frame duration the microphone capture produces.
        const val SAMPLES_PER_FRAME = SAMPLE_RATE / 50
    }
}

/** Indexed MediaCodec buffers were added in API 21; Android 4.3 exposes the buffer arrays. */
private fun inputBufferAt(codec: MediaCodec, index: Int) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) codec.getInputBuffer(index)
    else codec.inputBuffers?.getOrNull(index)

private fun outputBufferAt(codec: MediaCodec, index: Int) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) codec.getOutputBuffer(index)
    else codec.outputBuffers?.getOrNull(index)
