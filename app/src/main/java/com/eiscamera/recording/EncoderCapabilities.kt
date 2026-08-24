package com.eiscamera.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Size

/**
 * V1.1b-1: queries this device's ACTUAL supported video encoding
 * capabilities rather than assuming H.264 "must" work at whatever
 * resolution the live preview happens to use — spec section 18
 * ("investigate available H.264/H.265, resolution, FPS, bitrate,
 * hardware encoder") and this project's running "measure, don't assume"
 * discipline, already applied to camera preview sizes in V1.0a's
 * CameraSessionUtils.choosePreviewSize.
 */
object EncoderCapabilities {

    /** H.264/AVC: the broadest-support baseline. H.265/HEVC is a later,
     *  evidence-based upgrade once this path is proven (spec section 12
     *  — don't reach for the more sophisticated option first). */
    const val MIME_TYPE_AVC = "video/avc"

    /** The first available encoder for [mimeType] (REGULAR_CODECS lists
     *  codecs in the platform's own preferred order, hardware encoders
     *  generally first) — or null if this device genuinely has none,
     *  which MUST be checked, never assumed present. */
    fun findEncoder(mimeType: String = MIME_TYPE_AVC): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }

    /**
     * Clamps [targetWidth]x[targetHeight] to a size [codecInfo] actually
     * supports for [mimeType]. Encoders commonly have both a min/max
     * range AND an alignment requirement (e.g. width must be a multiple
     * of 16) — assuming an arbitrary preview resolution "just works" for
     * encoding is a real, common source of configure() failures.
     */
    fun chooseSupportedSize(codecInfo: MediaCodecInfo, mimeType: String, targetWidth: Int, targetHeight: Int): Size {
        val videoCaps = codecInfo.getCapabilitiesForType(mimeType).videoCapabilities
        val alignedWidth = alignDown(videoCaps.supportedWidths.clamp(targetWidth), videoCaps.widthAlignment)
        val alignedHeight = alignDown(videoCaps.getSupportedHeightsFor(alignedWidth).clamp(targetHeight), videoCaps.heightAlignment)
        return Size(alignedWidth, alignedHeight)
    }

    private fun alignDown(value: Int, alignment: Int): Int {
        if (alignment <= 1) return value
        return (value / alignment) * alignment
    }
}
