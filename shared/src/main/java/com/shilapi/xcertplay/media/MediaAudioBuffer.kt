package com.shilapi.xcertplay.media

/**
 * Jitter buffer for streams mapped to the media channel. Wireless CarPlay delivers audio over the same Wi-Fi link as
 * video; radio gaps of several hundred milliseconds are normal, so music needs a buffer that
 * outlasts them. Calls, Siri and navigation prompts keep the small low-latency buffer.
 */
object MediaAudioBuffer {
    const val DEFAULT_MILLIS = 300
    val presets = listOf(DEFAULT_MILLIS, 500, 1000)

    private const val HEADROOM_MILLIS = 200 // room above the start level so bursts after a gap fit
    // Low-latency buffers are measured in time: voice decodes at 16 kHz, where a byte floor would
    // turn into seconds of extra latency. 166 ms / 41 ms match the old 16 KB / 4 KB at 48 kHz mono.
    private const val LOW_LATENCY_BUFFER_MILLIS = 166
    private const val LOW_LATENCY_START_MILLIS = 41

    fun sanitize(millis: Int): Int = millis.takeIf { it in presets } ?: DEFAULT_MILLIS

    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    /** AudioTrack capacity and the amount to queue before play() for one output stream. */
    fun plan(isMedia: Boolean, sampleRate: Int, channels: Int, minBufferBytes: Int, mediaMillis: Int): Plan {
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, bytesFor(bytesPerSecond, LOW_LATENCY_BUFFER_MILLIS)),
            startBytes = maxOf(minBufferBytes, bytesFor(bytesPerSecond, LOW_LATENCY_START_MILLIS)),
        )
        if (!isMedia) return lowLatency
        val start = (bytesPerSecond * sanitize(mediaMillis) / 1000).toInt()
        val capacity = (bytesPerSecond * (sanitize(mediaMillis) + HEADROOM_MILLIS) / 1000).toInt()
        return Plan(
            trackBufferBytes = maxOf(capacity, lowLatency.trackBufferBytes),
            startBytes = maxOf(start, lowLatency.startBytes),
        )
    }

    private fun bytesFor(bytesPerSecond: Long, millis: Int): Int =
        (bytesPerSecond * millis / 1000).toInt()

    /**
     * The device may grant a smaller AudioTrack than requested. Writes block while the track is
     * paused and full, so the start level must stay below the real capacity or play() never runs.
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int {
        if (actualCapacityBytes <= 0) return plannedStartBytes
        return minOf(plannedStartBytes, actualCapacityBytes - writeChunkBytes).coerceAtLeast(writeChunkBytes)
    }
}
