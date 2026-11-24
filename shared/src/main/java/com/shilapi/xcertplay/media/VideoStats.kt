package com.shilapi.xcertplay.media

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val windowStartNs = AtomicLong(nanoTime())
    private val lastArrivalNs = AtomicLong(0L)
    private val received = AtomicInteger()
    private val rendered = AtomicInteger()
    private val recoveries = AtomicInteger()
    private val bytes = AtomicLong()
    private val maxArrivalGapNs = AtomicLong()
    private val touchSamples = AtomicInteger()
    private val touchLatencySumNs = AtomicLong()
    private val maxTouchLatencyNs = AtomicLong()

    fun onReceived(size: Int) {
        val now = nanoTime()
        val previous = lastArrivalNs.getAndSet(now)
        if (previous != 0L) {
            val gap = now - previous
            if (gap in 1 until IDLE_GAP_NS) updateMax(maxArrivalGapNs, gap)
        }
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples.incrementAndGet()
            touchLatencySumNs.addAndGet(touchLatency)
            updateMax(maxTouchLatencyNs, touchLatency)
        }
        received.incrementAndGet()
        bytes.addAndGet(size.toLong())
    }

    fun onRendered() {
        rendered.incrementAndGet()
    }

    fun onRecovery() {
        recoveries.incrementAndGet()
    }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs.get()
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        val receivedCount = received.getAndSet(0)
        val renderedCount = rendered.getAndSet(0)
        val recoveryCount = recoveries.getAndSet(0)
        val byteCount = bytes.getAndSet(0)
        val maxGap = maxArrivalGapNs.getAndSet(0)
        val touchCount = touchSamples.getAndSet(0)
        val touchSum = touchLatencySumNs.getAndSet(0)
        val touchMax = maxTouchLatencyNs.getAndSet(0)
        if (receivedCount == 0 && touchCount == 0) {
            windowStartNs.set(now)
            return null
        }
        val touchAvgMs = if (touchCount == 0) -1 else touchSum / touchCount / 1_000_000
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            receivedCount / seconds, renderedCount / seconds, maxGap / 1_000_000,
            (byteCount * 8 / 1000 / seconds).toLong(), recoveryCount,
            touchAvgMs, touchMax / 1_000_000, touchCount, TouchLatencyProbe.maxSendNs / 1_000_000,
        )
        TouchLatencyProbe.maxSendNs = 0
        Log.i(TAG, line)
        windowStartNs.set(now)
        return line
    }

    private fun updateMax(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (value <= current) return
            if (target.compareAndSet(current, value)) return
        }
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}
