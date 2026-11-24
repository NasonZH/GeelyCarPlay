package com.shilapi.xcertplay.media

import java.util.concurrent.atomic.AtomicInteger

/**
 * 一帧视频数据 可带引用计数
 *
 * 解码队列和镜像解码器会同时持有一帧 最后一个持有者释放时缓冲归还池
 */
class VideoFrame(
    val bytes: ByteArray,
    val length: Int,
    private val recycle: (() -> Unit)? = null,
) {
    private val references = AtomicInteger(1)

    fun retain() {
        references.incrementAndGet()
    }

    fun release() {
        if (references.decrementAndGet() == 0) recycle?.invoke()
    }
}

/** 按 4KB 档位复用解密缓冲 空闲上限 8MB */
object VideoFramePool {
    private const val BUCKET_BYTES = 4 * 1024
    private const val MAX_IDLE_BYTES = 8 * 1024 * 1024
    private val buckets = HashMap<Int, ArrayList<ByteArray>>()
    private var idleBytes = 0

    @Synchronized fun acquire(size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        val capacity = ((size + BUCKET_BYTES - 1) / BUCKET_BYTES) * BUCKET_BYTES
        val bucket = buckets[capacity]
        if (bucket != null && bucket.isNotEmpty()) {
            idleBytes -= capacity
            return bucket.removeAt(bucket.size - 1)
        }
        return ByteArray(capacity)
    }

    @Synchronized fun release(buffer: ByteArray) {
        val capacity = buffer.size
        if (capacity == 0 || capacity > MAX_IDLE_BYTES) return
        if (idleBytes + capacity > MAX_IDLE_BYTES) return
        buckets.getOrPut(capacity) { ArrayList() }.add(buffer)
        idleBytes += capacity
    }
}
