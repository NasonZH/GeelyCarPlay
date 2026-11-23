// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.media

import io.github.jaredmdobson.concentus.OpusDecoder

/**
 * Software Opus decoder for head units whose firmware has no `audio/opus` MediaCodec.
 *
 * The GEELY/GKUI image is one of those: iOS sends CarPlay telephony (and, on some builds,
 * navigation) as Opus, `MediaCodec.createDecoderByType("audio/opus")` fails, and the track plays
 * silence. Concentus is a pure-Java Opus implementation, so decoding 20 ms mono voice frames costs
 * little and removes the dependency on the vendor codec.
 */
internal class SoftwareOpusDecoder(sampleRate: Int, private val channels: Int) : OpusVoiceDecoder {
    private val decoder: OpusDecoder = OpusDecoder(sampleRate, channels)
    private val pcm = ShortArray(MAX_FRAME_SAMPLES_PER_CHANNEL * channels.coerceAtLeast(1))

    override val backend: String get() = "concentus"

    /**
     * Decodes one Opus packet into [pcm] and returns the number of frames per channel, or 0 when the
     * packet is malformed.
     */
    override fun decode(packet: ByteArray): Int {
        val frames = runCatching {
            decoder.decode(
                packet, 0, packet.size,
                pcm, 0, MAX_FRAME_SAMPLES_PER_CHANNEL, false,
            )
        }.getOrNull() ?: return 0
        return frames.coerceAtLeast(0)
    }

    /** Clears the decoder state after a discontinuity such as a jitter-buffer trim. */
    override fun reset() {
        runCatching { decoder.resetState() }
    }

    /** Interleaved 16-bit samples; valid up to `frames * channels` after [decode]. */
    override fun samples(): ShortArray = pcm

    /** The decoder holds no native resources; dropping the reference is enough. */
    override fun close() = Unit

    private companion object {
        // 120 ms at 48 kHz is the Opus maximum frame; CarPlay uses 20 ms (960 samples).
        const val MAX_FRAME_SAMPLES_PER_CHANNEL = 5760
    }
}
