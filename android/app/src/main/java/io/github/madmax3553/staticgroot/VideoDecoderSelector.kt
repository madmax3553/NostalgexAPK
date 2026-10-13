package io.github.madmax3553.staticgroot

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Keeps Dolby Vision files off the TV's Dolby Vision decoder.
 *
 * Measured on a MediaTek TV: a 4K HEVC Main 10 file carrying a Dolby Vision profile 8.1 layer
 * played through `OMX.dolby.vision.dvhe.st.decoder` and showed a green picture. Profile 8 (and 7)
 * keep a plain HEVC base layer, so an ordinary HEVC decoder plays it correctly, without the Dolby
 * enhancement. The selector therefore
 *  - answers a Dolby Vision request with the HEVC decoders, and
 *  - removes Dolby-named decoders from an HEVC request.
 * If that leaves no decoder it returns the original list, so nothing that played before stops.
 * Profile 5 has no compatible base layer, so it would still look wrong; the server should
 * transcode it, which is not handled here.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VideoDecoderSelector : MediaCodecSelector {

    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
        val original = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        return choose(mimeType, original, hevcInfos = {
            MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H265, requiresSecureDecoder, requiresTunnelingDecoder)
        }) { it.name }
    }

    /** Pure core, so the rule can be tested without a codec list. [name] reads a decoder's name. */
    fun <T> choose(mimeType: String, original: List<T>, hevcInfos: () -> List<T>, name: (T) -> String): List<T> {
        fun plain(list: List<T>) = list.filter { !name(it).contains("dolby", ignoreCase = true) && !name(it).contains("dvhe", ignoreCase = true) }
        return when (mimeType) {
            MimeTypes.VIDEO_DOLBY_VISION -> plain(hevcInfos()).ifEmpty { original }
            MimeTypes.VIDEO_H265 -> plain(original).ifEmpty { original }
            else -> original
        }
    }
}
