package com.shilapi.xcertplay.airplay

/**
 * RFC 8439 ChaCha20-Poly1305 的原生实现
 *
 * 车机 CPU 较弱，BouncyCastle 在大帧解密时会占用接收线程数百毫秒。
 * 原生实现不可用时回退到 BouncyCastle。
 */
object NativeChaCha20Poly1305 {
    val isAvailable: Boolean = runCatching { System.loadLibrary("diplay_crypto") }.isSuccess

    /** 返回明文长度；认证失败返回 -1，参数错误返回 -2。 */
    external fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        output: ByteArray,
        outputOffset: Int,
    ): Int

    /** 返回密文加 tag 的总长度。 */
    external fun seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
        plaintextOffset: Int,
        plaintextLength: Int,
        output: ByteArray,
        outputOffset: Int,
    ): Int
}
