package com.eiscamera.processing

import kotlinx.serialization.Serializable

/**
 * CPU / GPU / OS / encoder inventory.
 *
 * glRenderer/glVendor/glVersion/glExtensions come from a real (but trivial,
 * 1x1 pbuffer) EGL context created by [GpuInfoProbe] — this is a genuine
 * driver query, not a guess. [eglQuerySucceeded] records whether that probe
 * actually completed; if false, the GL fields are null and must be treated
 * as UNAVAILABLE, not as "device has no GPU."
 *
 * [hardwareVideoEncoders] is a DECLARED codec list (from MediaCodecList),
 * not a measured encode-throughput benchmark — see roadmap "Processing /
 * Performance Test" stage for that.
 */
@Serializable
data class ProcessingInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val androidRelease: String,
    val apiLevel: Int,
    val supportedAbis: List<String>,
    val cpuCoreCount: Int,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowRamDevice: Boolean,
    val glRenderer: String?,
    val glVendor: String?,
    val glVersion: String?,
    val glExtensions: List<String>,
    val eglQuerySucceeded: Boolean,
    val hardwareVideoEncoders: List<CodecInfoSummary>,
)

@Serializable
data class CodecInfoSummary(
    val name: String,
    val mimeType: String,
    /**
     * API >= 29 (Q): MEASURED-equivalent — read directly from
     * MediaCodecInfo.isHardwareAccelerated(), which the platform guarantees.
     * API < 29: ESTIMATED via a name-based heuristic (OMX.google.* /
     * c2.android.* prefixes are Google's software codecs on most OEM
     * builds) — this is NOT a platform-guaranteed signal below API 29.
     * This distinction matters because encoder selection for real-time EIS
     * (roadmap V1.x) should prefer confirmed hardware encoders.
     */
    val isHardwareAccelerated: Boolean,
    val maxSupportedWidth: Int,
    val maxSupportedHeight: Int,
    val supportedColorFormats: List<Int>,
)
