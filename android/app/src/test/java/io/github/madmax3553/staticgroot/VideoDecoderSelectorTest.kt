package io.github.madmax3553.staticgroot

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDecoderSelectorTest {
    private val dv = "OMX.dolby.vision.dvhe.st.decoder"
    private val hw = "OMX.MTK.VIDEO.DECODER.HEVC"
    private val sw = "c2.android.hevc.decoder"
    private fun choose(mime: String, original: List<String>, hevc: List<String> = listOf(hw, sw)) =
        VideoDecoderSelector.choose(mime, original, { hevc }) { it }

    @Test fun dolbyVisionRequestGetsThePlainHevcDecoders() =
        assertEquals(listOf(hw, sw), choose(MimeTypes.VIDEO_DOLBY_VISION, listOf(dv)))

    @Test fun hevcRequestDropsTheDolbyDecoderButKeepsOrder() =
        assertEquals(listOf(hw, sw), choose(MimeTypes.VIDEO_H265, listOf(dv, hw, sw)))

    @Test fun ifOnlyTheDolbyDecoderExistsItIsStillUsed() {
        assertEquals(listOf(dv), choose(MimeTypes.VIDEO_H265, listOf(dv)))
        assertEquals(listOf(dv), choose(MimeTypes.VIDEO_DOLBY_VISION, listOf(dv), hevc = listOf(dv)))
    }

    @Test fun otherCodecsAreUntouched() =
        assertEquals(listOf("c2.h264"), choose(MimeTypes.VIDEO_H264, listOf("c2.h264")))
}
