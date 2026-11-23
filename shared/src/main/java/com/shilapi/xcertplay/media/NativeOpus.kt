package com.shilapi.xcertplay.media

/**
 * JNI binding for the bundled libopus (`libdiplay_opus.so`).
 *
 * The GEELY head unit has no `audio/opus` MediaCodec, so CarPlay voice is decoded by libopus.
 * Unlike Concentus 1.0.2, libopus decodes hybrid packets at any output sample rate, which is what
 * makes 16 kHz voice decoding possible later.
 */
internal object NativeOpus {
    /** False when the library is missing or the ABI is unsupported; callers fall back to Concentus. */
    val isAvailable: Boolean = runCatching { System.loadLibrary("diplay_opus") }.isSuccess

    @JvmStatic external fun nativeDecoderCreate(sampleRate: Int, channels: Int): Long

    @JvmStatic external fun nativeDecoderDecode(
        handle: Long,
        packet: ByteArray,
        packetOffset: Int,
        packetBytes: Int,
        pcm: ShortArray,
        pcmOffset: Int,
        maxFramesPerChannel: Int,
    ): Int

    @JvmStatic external fun nativeDecoderReset(handle: Long)

    @JvmStatic external fun nativeDecoderDestroy(handle: Long)

    @JvmStatic external fun nativeEncoderCreate(
        sampleRate: Int,
        channels: Int,
        bitrate: Int,
        complexity: Int,
    ): Long

    @JvmStatic external fun nativeEncode(
        handle: Long,
        pcm: ShortArray,
        pcmOffset: Int,
        frameSamples: Int,
        out: ByteArray,
        outOffset: Int,
        maxOutBytes: Int,
    ): Int

    @JvmStatic external fun nativeEncoderDestroy(handle: Long)
}
