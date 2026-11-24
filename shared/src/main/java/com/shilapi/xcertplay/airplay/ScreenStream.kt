package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.media.VideoFrame
import com.shilapi.xcertplay.media.VideoFramePool
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(private val key: ByteArray, private val onDiagnostic: (String) -> Unit = {}) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(frame: VideoFrame) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private val nonceBuffer = ByteArray(AirPlayCrypto.NONCE_SIZE)
    /** 复用密文负载缓冲 解密输出仍按帧分配 */
    private var bodyBuffer = ByteArray(INITIAL_BODY_BYTES)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        com.shilapi.xcertplay.compat.WildcardBind.bind(bound)
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) {
                runCatching { onDiagnostic("视频接入退出: error=${error.javaClass.simpleName}") }
                listener.onClosed(error)
            }
        }
    }

    private fun run(sock: Socket) {
        var failure: Throwable? = null
        var exitReason = "读取循环结束"
        var stage = "获取输入流"
        val stats = StreamReceiveStats("video", onDiagnostic)
        try {
            val input = sock.getInputStream()
            // 复用头部与负载缓冲 解密输出仍按帧分配 由解码队列持有
            val headerBuffer = ByteArray(HEADER_LEN)
            while (!closed.get()) {
                stats.reading()
                stage = "读取头部"
                val header = readFully(input, headerBuffer)
                if (header == null) {
                    exitReason = "头部 EOF"
                    break
                }
                val bodySize = readU32Le(header, 0)
                if (bodySize > MAX_BODY) {
                    exitReason = "负载长度超限 length=$bodySize"
                    break
                }
                stage = "读取负载"
                if (bodySize > bodyBuffer.size) {
                    bodyBuffer = ByteArray(maxOf(bodySize, bodyBuffer.size * 2))
                }
                val body = readFully(input, bodyBuffer, bodySize)
                if (body == null) {
                    exitReason = "负载读取结束 length=$bodySize"
                    break
                }
                stats.received(HEADER_LEN + bodySize)
                stage = "处理消息"
                onMessage(header, body, bodySize)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
            exitReason = "异常 error=${error.javaClass.simpleName}"
        } finally {
            stats.flush(ended = true)
            runCatching { onDiagnostic("视频读取退出: 原因=$exitReason stage=$stage localClosed=${closed.get()}") }
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(header: ByteArray, body: ByteArray, bodySize: Int) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                val frame = if (bodySize >= ScreenCodec.TAG_SIZE) {
                    val plainLength = bodySize - ScreenCodec.TAG_SIZE
                    val buffer = VideoFramePool.acquire(plainLength)
                    val decoded = ScreenCodec.decryptFrameInto(
                        key,
                        frameCounter.get(),
                        header,
                        body,
                        bodySize,
                        nonceBuffer,
                        buffer,
                    ).also { frameCounter.incrementAndGet() }
                    val annexBLength = ScreenCodec.lengthPrefixedToAnnexB(buffer, decoded)
                    VideoFrame(buffer, annexBLength) { VideoFramePool.release(buffer) }
                } else {
                    val buffer = VideoFramePool.acquire(bodySize)
                    body.copyInto(buffer, 0, 0, bodySize)
                    VideoFrame(buffer, bodySize) { VideoFramePool.release(buffer) }
                }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=$bodySize plain=${frame.length} " +
                        "head=${frame.bytes.hexPrefix(16)}",
                    )
                }
                listener.onFrame(frame)
            }
            OP_VIDEO_CONFIG -> {
                val configBody = body.copyOf(bodySize)
                val (codec, codecData) = ScreenCodec.detectConfig(configBody)
                Log.i(TAG, "video codec config codec=$codec body=$bodySize data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(input: InputStream, into: ByteArray, length: Int): ByteArray? {
        if (length < 0) return null
        var offset = 0
        while (offset < length) {
            val read = input.read(into, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return into
    }

    /** 读满已有缓冲 用于复用固定长度的头部 */
    private fun readFully(input: InputStream, into: ByteArray): ByteArray? {
        var offset = 0
        while (offset < into.size) {
            val read = input.read(into, offset, into.size - offset)
            if (read < 0) return null
            offset += read
        }
        return into
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
        const val INITIAL_BODY_BYTES = 128 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        decryptFrame(key, counter, header, body, body.size, ByteArray(AirPlayCrypto.NONCE_SIZE))

    /** 复用 nonce 缓冲的版本 由调用方保证单线程访问 */
    fun decryptFrame(
        key: ByteArray,
        counter: Long,
        header: ByteArray,
        body: ByteArray,
        nonce: ByteArray,
    ): ByteArray = decryptFrame(key, counter, header, body, body.size, nonce)

    /** 明文从复用缓冲的前 bodySize 字节解密 */
    fun decryptFrame(
        key: ByteArray,
        counter: Long,
        header: ByteArray,
        body: ByteArray,
        bodySize: Int,
        nonce: ByteArray,
    ): ByteArray =
        if (bodySize < TAG_SIZE) body.copyOf(bodySize)
        else AirPlayCrypto.chachaOpen(
            key,
            AirPlayCrypto.nonce64Into(counter, nonce),
            body,
            0,
            bodySize,
            header,
        )

    /** 明文直接写入复用缓冲 返回明文长度 */
    fun decryptFrameInto(
        key: ByteArray,
        counter: Long,
        header: ByteArray,
        body: ByteArray,
        bodySize: Int,
        nonce: ByteArray,
        output: ByteArray,
    ): Int = AirPlayCrypto.chachaOpenInto(
        key,
        AirPlayCrypto.nonce64Into(counter, nonce),
        body,
        0,
        bodySize,
        header,
        output,
    )

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        lengthPrefixedToAnnexB(payload, payload.size)
        return payload
    }

    /** 原地转换前 length 个字节 返回可解码的长度 */
    fun lengthPrefixedToAnnexB(payload: ByteArray, length: Int): Int {
        if (length < 4 || payload.startsWithStartCode(length)) return length

        var offset = 0
        while (offset + 4 <= length) {
            val nalLength = readU32Be(payload, offset)
            offset += 4
            if (nalLength <= 0 || offset + nalLength > length) return length
            offset += nalLength
        }
        if (offset != length) return length

        offset = 0
        while (offset + 4 <= length) {
            val nalLength = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + nalLength
        }
        return length
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean = startsWithStartCode(size)

private fun ByteArray.startsWithStartCode(length: Int): Boolean =
    length >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
