package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedNs: Long = System.nanoTime()) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data class DetachSurface(val surface: Surface, val completed: CountDownLatch) : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    // 计数代替每帧遍历队列 解码线程取走时同步递减
    private val frameCount = AtomicInteger(0)
    private val frameBytes = AtomicLong(0)

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            if (frameCount.get() >= maxFrames || frameBytes.get() + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) return
            frameCount.incrementAndGet()
            frameBytes.addAndGet(job.nalus.size.toLong())
        }
        jobs.offer(job)
    }

    @Synchronized fun discardFrames() {
        // Collection.removeIf is a Java default method added in API 24; use the iterator on 4.3.
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (iterator.next()) {
                is VideoJob.Frame, is VideoJob.Resync -> iterator.remove()
                else -> Unit
            }
        }
        frameCount.set(0)
        frameBytes.set(0)
    }

    fun completeSurfaceDetaches() {
        jobs.filterIsInstance<VideoJob.DetachSurface>().forEach { it.completed.countDown() }
    }

    fun poll(timeoutMillis: Long): VideoJob? {
        val job = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        if (job is VideoJob.Frame) {
            if (frameCount.get() > 0) frameCount.decrementAndGet()
            if (frameBytes.addAndGet(-job.nalus.size.toLong()) < 0) frameBytes.set(0)
        }
        return job
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
