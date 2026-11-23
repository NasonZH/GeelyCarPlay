package com.shilapi.xcertplay.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@Implements(MediaCodecList::class)
class OpusCodecListShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun getCodecCount(): Int = 1

        @JvmStatic
        @Implementation
        fun getCodecInfoAt(index: Int): MediaCodecInfo {
            require(index == 0)
            return Shadow.newInstanceOf(MediaCodecInfo::class.java)
        }
    }
}

@Implements(MediaCodecInfo::class)
class OpusCodecInfoShadow {
    @Implementation
    fun isEncoder(): Boolean = false

    @Implementation
    fun getSupportedTypes(): Array<String> = arrayOf("audio/opus")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [OpusCodecListShadow::class, OpusCodecInfoShadow::class])
class OpusOutputFormatTest {
    @Test
    fun platformOpusUses48kRegardlessOfRequestedVoiceRate() {
        for (rate in listOf(16_000, 24_000, 48_000)) {
            val input = AudioFormat(AudioCodecKind.OPUS, rate, 1, 101, "default")
            assertEquals(input.copy(sampleRate = 48_000), opusOutputFormat(input))
        }
    }

    @Test
    fun aacAndPcmKeepTheirOriginalFormat() {
        for (codec in listOf(AudioCodecKind.AAC_LC, AudioCodecKind.LPCM)) {
            val input = AudioFormat(codec, 16_000, 1, 100, "default")
            assertSame(input, opusOutputFormat(input))
        }
    }
}
