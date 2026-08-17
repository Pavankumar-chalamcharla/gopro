package com.eiscamera.capability

/**
 * Stabilization capability levels, per spec section 3.
 * Ordinal order matters (used for comparisons):
 *   UNSUPPORTED < LEVEL_1_BASIC < LEVEL_2_ADVANCED < LEVEL_3_ROLLING_SHUTTER < LEVEL_4_FULL
 */
enum class CapabilityLevel(val label: String) {
    UNSUPPORTED("Unsupported"),
    LEVEL_1_BASIC("Basic EIS"),
    LEVEL_2_ADVANCED("Advanced EIS"),
    LEVEL_3_ROLLING_SHUTTER("Advanced EIS + Rolling Shutter"),
    LEVEL_4_FULL("Full Stabilization"),
}

/**
 * The result of running the CapabilityEngine, always accompanied by the
 * documented reasons that produced it (spec section 42: never claim a
 * capability the engine has no evidence for).
 */
data class CapabilityResult(
    val level: CapabilityLevel,
    val reasons: List<String>,
    /**
     * True only when every input the classification depended on was
     * MEASURED (not merely AVAILABLE/DECLARED/ESTIMATED). At V0.2 this is
     * always false for any non-UNSUPPORTED result, because sensor/camera
     * QUALITY has not been measured yet — see CapabilityEngine kdoc.
     */
    val fullyEvidenced: Boolean,
)
