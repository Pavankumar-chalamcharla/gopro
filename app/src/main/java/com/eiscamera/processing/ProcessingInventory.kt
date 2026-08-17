package com.eiscamera.processing

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import com.eiscamera.logging.EisLog

/**
 * Static + lightly-probed inventory of CPU/GPU/OS/codec capabilities.
 *
 * This is NOT a performance benchmark (see roadmap "Processing / Performance
 * Test" stage, not yet implemented) — it only enumerates what the platform
 * DECLARES, plus one direct GPU driver query via [GpuInfoProbe].
 */
class ProcessingInventory(private val context: Context) {

    fun scan(): ProcessingInfo {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val gpu = GpuInfoProbe.probe()
        if (!gpu.succeeded) {
            EisLog.w(EisLog.Tag.GPU, "GPU probe did not succeed: ${gpu.error}")
        } else {
            EisLog.i(EisLog.Tag.GPU, "GPU renderer='${gpu.renderer}' vendor='${gpu.vendor}'")
        }

        return ProcessingInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            totalMemoryBytes = memInfo.totalMem,
            availableMemoryBytes = memInfo.availMem,
            lowRamDevice = am.isLowRamDevice,
            glRenderer = gpu.renderer,
            glVendor = gpu.vendor,
            glVersion = gpu.version,
            glExtensions = gpu.extensions,
            eglQuerySucceeded = gpu.succeeded,
            hardwareVideoEncoders = enumerateHardwareEncoders(),
        )
    }

    private fun enumerateHardwareEncoders(): List<CodecInfoSummary> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val results = mutableListOf<CodecInfoSummary>()
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (!type.startsWith("video/")) continue
                val hwAccelerated = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> info.isHardwareAccelerated
                    // Pre-Q heuristic only — ESTIMATED, not MEASURED (spec section 41).
                    else -> !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                }
                var maxW = -1
                var maxH = -1
                val colorFormats = mutableListOf<Int>()
                try {
                    val caps = info.getCapabilitiesForType(type)
                    caps.videoCapabilities?.let { vc ->
                        maxW = vc.supportedWidths.upper
                        maxH = vc.supportedHeights.upper
                    }
                    colorFormats += caps.colorFormats.toList()
                } catch (e: Exception) {
                    EisLog.w(EisLog.Tag.PROCESSING, "Could not read capabilities for ${info.name}/$type", e)
                }
                results += CodecInfoSummary(
                    name = info.name,
                    mimeType = type,
                    isHardwareAccelerated = hwAccelerated,
                    maxSupportedWidth = maxW,
                    maxSupportedHeight = maxH,
                    supportedColorFormats = colorFormats,
                )
            }
        }
        return results
    }
}
