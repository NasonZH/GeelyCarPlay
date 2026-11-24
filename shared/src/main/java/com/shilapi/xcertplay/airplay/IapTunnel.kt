package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the wired iAP2 relay.
 */
class IapTunnel(
    private val readKey: ByteArray,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val readCounter = AtomicLong(0)
    private val bindAddress =
        if (bindAddress is Inet4Address) InetAddress.getByName("0.0.0.0") else bindAddress
    private val servers = mutableListOf<ServerSocket>()
    private var socket: Socket? = null
    private val threads = mutableListOf<Thread>()
    private val peerConnected = CountDownLatch(1)
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = bindAny()
        servers += bound
        listener.onDebug("AirPlay iAP tunnel listener bound=${bound.localSocketAddress}")
        // Android 4.3 cannot bind the IPv6 wildcard. Its default AF_INET6 socket is dual-stack,
        // so the IPv4 wildcard accepts both IPv4 and IPv6 peers without a second listener.
        servers.forEach { server ->
            threads += Thread({ accept(server) }, "airplay-iap-tunnel").apply {
                isDaemon = true
                start()
            }
        }
        return bound.localPort
    }

    private fun bindAny(): ServerSocket =
        com.shilapi.xcertplay.compat.WildcardBind.bind(ServerSocket())

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        servers.toList().forEach(::safeClose)
        servers.clear()
        threads.toList().forEach(Thread::interrupt)
        threads.clear()
        peerConnected.countDown()
    }

    /** Waits until the iPhone has connected to the advertised dataPort. */
    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun accept(bound: ServerSocket) {
        listener.onDebug(
            "AirPlay iAP tunnel accepting local=${bound.localSocketAddress}",
        )
        while (!closed.get()) {
            val accepted = try {
                bound.accept()
            } catch (error: Exception) {
                if (!closed.get()) listener.onClosed(error)
                return
            }
            if (closed.get()) {
                safeClose(accepted)
                return
            }
            accepted.setSoLinger(true, 0)
            socket = accepted
            readCounter.set(0)
            peerConnected.countDown()
            listener.onOpen(accepted.remoteSocketAddress?.toString())
            run(accepted)
        }
    }

    private fun run(sock: Socket) {
        var ciphertext = ByteArray(READ_CHUNK_BYTES)
        var cipherLength = 0
        var plaintext = ByteArray(READ_CHUNK_BYTES)
        var plainLength = 0
        var failure: Throwable? = null
        var announcedData = false
        try {
            val input = sock.getInputStream()
            val chunk = ByteArray(READ_CHUNK_BYTES)
            val frameAad = ByteArray(FRAME_HEADER_LEN)
            while (!closed.get()) {
                val read = input.read(chunk)
                if (read < 0) {
                    listener.onDebug("AirPlay iAP tunnel peer EOF")
                    break
                }
                if (!announcedData) {
                    announcedData = true
                    listener.onDebug("AirPlay iAP tunnel received data")
                }
                if (cipherLength + read > ciphertext.size) {
                    ciphertext = growBuffer(ciphertext, cipherLength + read)
                }
                System.arraycopy(chunk, 0, ciphertext, cipherLength, read)
                cipherLength += read

                var frameOffset = 0
                while (cipherLength - frameOffset >= FRAME_HEADER_LEN) {
                    val length = readU16Le(ciphertext, frameOffset)
                    val frameLength = FRAME_HEADER_LEN + length + TAG_SIZE
                    if (cipherLength - frameOffset < frameLength) break
                    System.arraycopy(ciphertext, frameOffset, frameAad, 0, FRAME_HEADER_LEN)
                    val plain = AirPlayCrypto.chachaOpen(
                        readKey,
                        AirPlayCrypto.nonce64(readCounter.get()),
                        ciphertext,
                        frameOffset + FRAME_HEADER_LEN,
                        length + TAG_SIZE,
                        frameAad,
                    )
                    readCounter.incrementAndGet()
                    if (plainLength + plain.size > plaintext.size) {
                        plaintext = growBuffer(plaintext, plainLength + plain.size)
                    }
                    System.arraycopy(plain, 0, plaintext, plainLength, plain.size)
                    plainLength += plain.size
                    frameOffset += frameLength
                }
                if (frameOffset > 0) {
                    System.arraycopy(ciphertext, frameOffset, ciphertext, 0, cipherLength - frameOffset)
                    cipherLength -= frameOffset
                }

                var packageOffset = 0
                while (plainLength - packageOffset >= PACKAGE_HEADER_LEN) {
                    val size = readU32Be(plaintext, packageOffset)
                    if (size < PACKAGE_HEADER_LEN || size > MAX_PACKAGE) break
                    if (plainLength - packageOffset < size) break
                    val messageType = readU32Be(plaintext, packageOffset + MESSAGE_TYPE_OFFSET)
                    if (messageType == MSG_TYPE_COMM) {
                        // Per-package iAP traffic is only useful during a protocol investigation.
                        if (AirPlayTrace.enabled) {
                            listener.onDebug(
                                "AirPlay iAP tunnel package type=comm body=${size - PACKAGE_HEADER_LEN}",
                            )
                        }
                        listener.onIap(
                            plaintext.copyOfRange(
                                packageOffset + PACKAGE_HEADER_LEN,
                                packageOffset + size,
                            ),
                        )
                    }
                    packageOffset += size
                }
                if (packageOffset > 0) {
                    System.arraycopy(plaintext, packageOffset, plaintext, 0, plainLength - packageOffset)
                    plainLength -= packageOffset
                }
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get() && failure != null) listener.onClosed(failure)
        }
    }

    /** 按需扩容 保留已有内容 避免逐块整体复制 */
    private fun growBuffer(buffer: ByteArray, minimumSize: Int): ByteArray {
        var capacity = buffer.size.coerceAtLeast(READ_CHUNK_BYTES)
        while (capacity < minimumSize) {
            capacity = if (capacity > Int.MAX_VALUE / 2) minimumSize else capacity * 2
        }
        return buffer.copyOf(capacity)
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val FRAME_HEADER_LEN = 2
        const val TAG_SIZE = 16
        const val PACKAGE_HEADER_LEN = 32
        const val MESSAGE_TYPE_OFFSET = 16
        const val MSG_TYPE_COMM = 0x636f6d6d
        const val MAX_PACKAGE = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024
    }
}
