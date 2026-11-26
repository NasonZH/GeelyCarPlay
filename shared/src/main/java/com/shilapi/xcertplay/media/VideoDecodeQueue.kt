package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val frame: VideoFrame, val receivedNs: Long = System.nanoTime()) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data class DetachSurface(val surface: Surface, val completed: CountDownLatch) : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, length: Int, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, length, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val jobs = ArrayDeque<VideoJob>()
    private var acceptingFrames = true
    private var frameCount = 0
    private var frameBytes = 0L

    fun offer(job: VideoJob) = lock.withLock {
        if (job is VideoJob.Frame) {
            if (!acceptingFrames) {
                job.frame.release()
                return@withLock
            }
            val length = job.frame.length
            if (frameCount >= maxFrames || frameBytes + length > maxBytes) {
                discardFramesLocked()
                jobs.addLast(VideoJob.Resync)
                available.signal()
            }
            // A single oversized frame is also a lost reference chain.
            if (length > maxBytes) {
                job.frame.release()
                return@withLock
            }
            frameCount++
            frameBytes += length
        }
        jobs.addLast(job)
        available.signal()
    }

    fun discardFrames() = lock.withLock { discardFramesLocked() }

    fun stopAcceptingFrames() = lock.withLock {
        if (!acceptingFrames) return@withLock
        acceptingFrames = false
        discardFramesLocked()
    }

    private fun discardFramesLocked() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (val job = iterator.next()) {
                is VideoJob.Frame -> {
                    iterator.remove()
                    job.frame.release()
                }
                is VideoJob.Resync -> iterator.remove()
                else -> Unit
            }
        }
        frameCount = 0
        frameBytes = 0
    }

    fun completeSurfaceDetaches() = lock.withLock {
        for (job in jobs) {
            if (job is VideoJob.DetachSurface) job.completed.countDown()
        }
    }

    fun poll(timeoutMillis: Long): VideoJob? = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (jobs.isEmpty() && remaining > 0L) {
            remaining = available.awaitNanos(remaining)
        }
        val job = jobs.pollFirst()
        if (job is VideoJob.Frame) {
            frameCount--
            frameBytes -= job.frame.length
        }
        job
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
