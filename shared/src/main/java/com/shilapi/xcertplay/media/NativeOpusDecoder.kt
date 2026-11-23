package com.shilapi.xcertplay.media

/**
 * Opus decoder backed by the bundled libopus, used on head units whose firmware has no
 * `audio/opus` MediaCodec. Decodes at any output sample rate, including 16 kHz voice.
 */
internal class NativeOpusDecoder(sampleRate: Int, channels: Int) : OpusVoiceDecoder {
    private var handle = NativeOpus.nativeDecoderCreate(sampleRate, channels)
    private val pcm = ShortArray(MAX_FRAME_SAMPLES_PER_CHANNEL * channels.coerceAtLeast(1))

    override val backend: String get() = "native"

    /** False when libopus refused to create a decoder for this rate/channel count. */
    val isOpen: Boolean get() = handle != 0L

    override fun decode(packet: ByteArray): Int {
        val handle = handle
        if (handle == 0L) return 0
        val frames = NativeOpus.nativeDecoderDecode(
            handle,
            packet,
            0,
            packet.size,
            pcm,
            0,
            MAX_FRAME_SAMPLES_PER_CHANNEL,
        )
        return frames.coerceAtLeast(0)
    }

    override fun reset() {
        val handle = handle
        if (handle != 0L) NativeOpus.nativeDecoderReset(handle)
    }

    override fun samples(): ShortArray = pcm

    override fun close() {
        val handle = handle
        this.handle = 0L
        if (handle != 0L) NativeOpus.nativeDecoderDestroy(handle)
    }

    private companion object {
        // 120 ms at 48 kHz is the Opus maximum frame; CarPlay uses 20 ms (960 samples).
        const val MAX_FRAME_SAMPLES_PER_CHANNEL = 5760
    }
}
