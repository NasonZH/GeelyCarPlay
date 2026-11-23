package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.io.Closeable

/**
 * One Opus decode backend: [NativeOpusDecoder] wraps the bundled libopus, [SoftwareOpusDecoder] is
 * the pure-Java fallback for builds where the native library cannot be loaded.
 */
internal interface OpusVoiceDecoder : Closeable {
    /** Name of the backend, for the diagnostic log. */
    val backend: String

    /**
     * Decodes one Opus packet into the sample buffer and returns the number of frames per channel,
     * or 0 when the packet is malformed.
     */
    fun decode(packet: ByteArray): Int

    /** Clears the decoder state after a discontinuity such as a jitter-buffer trim. */
    fun reset()

    /** Interleaved 16-bit samples; valid up to `frames * channels` after [decode]. */
    fun samples(): ShortArray
}

/** libopus when the bundled library loads, Concentus otherwise; null when both are unavailable. */
internal fun openOpusVoiceDecoder(sampleRate: Int, channels: Int): OpusVoiceDecoder? {
    if (NativeOpus.isAvailable) {
        val native = NativeOpusDecoder(sampleRate, channels)
        if (native.isOpen) return native
        native.close()
    }
    return runCatching { SoftwareOpusDecoder(sampleRate, channels) }.getOrNull()
}

/** 系统 Opus 解码器输出 48 kHz, 只有无系统解码器的 libopus 语音路径使用 16 kHz */
internal fun opusOutputFormat(format: AudioFormat): AudioFormat {
    if (format.codec != AudioCodecKind.OPUS) return format
    if (AudioCodecCapabilities.opusDecoder) return format.copy(sampleRate = PLATFORM_OPUS_SAMPLE_RATE)
    if (format.audioType.equals("media", ignoreCase = true)) return format
    if (!NativeOpus.isAvailable) return format
    return format.copy(sampleRate = VOICE_OPUS_SAMPLE_RATE)
}

private const val PLATFORM_OPUS_SAMPLE_RATE = 48_000
private const val VOICE_OPUS_SAMPLE_RATE = 16_000
