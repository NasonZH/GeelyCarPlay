// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.media

import android.media.MediaCodecList

/**
 * Which audio decoders this device actually has.
 *
 * The GEELY/GKUI Android 4.3 image ships without an `audio/opus` decoder, so advertising Opus in the
 * AirPlay /info response made the iPhone send CarPlay navigation and alert audio as Opus. The decoder
 * could not be created (`Unable to instantiate a decoder for type 'audio/opus'`), the navigation
 * stream stayed silent while AAC music kept playing. The info plist now only advertises Opus when a
 * decoder exists, so those units negotiate PCM instead.
 */
object AudioCodecCapabilities {
    val opusDecoder: Boolean by lazy { hasDecoder("audio/opus") }

    @Suppress("DEPRECATION")
    private fun hasDecoder(mime: String): Boolean = try {
        // MediaCodecList(REGULAR_CODECS) is API 21; the index API is available on API 16.
        val count = MediaCodecList.getCodecCount()
        (0 until count).any { index ->
            val info = MediaCodecList.getCodecInfoAt(index) ?: return@any false
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    } catch (error: Throwable) {
        false
    }
}
