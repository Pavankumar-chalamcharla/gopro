package com.eiscamera.capability

/**
 * All numeric thresholds used by [CapabilityEngine], collected in one place
 * per spec section 33 ("avoid magic numbers ... every engineering threshold
 * must be documented").
 *
 * STATUS AT V0.2: the scanner currently produces only STATIC / DECLARED
 * data — sensor existence + declared rates, static Camera2 characteristics,
 * and a static processing/GPU inventory. It does NOT yet measure real
 * sampling rate, jitter, noise, bias, or frame-timing stability.
 * Consequently, thresholds that depend on MEASURED quality are defined here
 * for forward documentation but are explicitly marked NOT YET APPLIED —
 * applying them without real measurements would violate spec section 42
 * ("no false claims").
 *
 * Every constant documents:
 *   MEANING        – what it represents physically
 *   UNITS
 *   ORIGIN         – where the number comes from
 *   LOWER EFFECT   – what happens if you decrease it
 *   HIGHER EFFECT  – what happens if you increase it
 *   DEVICE-DEPENDENT? – whether the same value is expected to generalize
 *   TUNED?         – experimentally tuned vs. first-principles estimate
 */
object CapabilityThresholds {

    // -----------------------------------------------------------------
    // GYROSCOPE RATE (declared) — APPLIED at V0.2, as a coarse pre-check.
    // -----------------------------------------------------------------

    /**
     * MEANING: Minimum DECLARED gyroscope sampling rate (derived from
     *   Sensor.getMinDelay()) below which we do not even attempt Basic EIS,
     *   because there would not be enough angular-rate samples per video
     *   frame to build a meaningful orientation curve at typical 30fps
     *   capture.
     * UNITS: Hz.
     * ORIGIN: Engineering estimate from Nyquist-style reasoning. Human hand
     *   tremor / hand-shake content is generally reported in the ~4-12 Hz
     *   band in the EIS/video-stabilization literature. To resolve motion
     *   content up to roughly 15 Hz with margin for filter group delay, the
     *   sampling rate should sit well above 2x that band.
     * LOWER EFFECT: Allowing lower rates risks aliasing high-frequency
     *   shake into the estimated orientation, or under-sampling fast
     *   rotations, producing visible residual jitter.
     * HIGHER EFFECT: Requiring a higher rate excludes more devices from
     *   even Basic EIS — including devices whose real rate is fine but
     *   whose DECLARED rate happens to be conservative, which is exactly
     *   why V0.3 measures the ACTUAL rate instead of trusting this number
     *   for anything beyond a coarse early filter.
     * DEVICE-DEPENDENT: No — this is about hand-shake signal content, not
     *   any specific device's hardware.
     * TUNED: NOT experimentally tuned yet. This is a defensible starting
     *   point, to be revisited once V0.3 collects real jitter/rate data
     *   across multiple devices.
     */
    const val MIN_DECLARED_GYRO_RATE_HZ_FOR_BASIC: Double = 50.0

    /**
     * MEANING: Declared gyro rate above which Advanced EIS becomes
     *   plausible, SUBJECT TO measured confirmation once V0.3 exists.
     * UNITS: Hz.
     * ORIGIN: Common high-quality mobile gyroscopes report 200-500Hz+;
     *   200Hz gives >10x margin over the ~12-15Hz hand-shake band used
     *   above.
     * LOWER/HIGHER EFFECT: same reasoning as MIN_DECLARED_GYRO_RATE_HZ_FOR_BASIC,
     *   shifted up.
     * DEVICE-DEPENDENT: No.
     * TUNED: NOT experimentally tuned yet.
     * APPLIED: NOT YET — reserved for use once V0.3 exists, since this
     *   engine currently never returns anything above LEVEL_1_BASIC.
     */
    const val MIN_DECLARED_GYRO_RATE_HZ_FOR_ADVANCED: Double = 200.0

    // -----------------------------------------------------------------
    // SENSOR QUALITY (measured) — APPLIED as of V0.3.
    // -----------------------------------------------------------------

    /**
     * MEANING: Max allowed measured sampling-interval jitter (standard
     *   deviation of inter-sample time) for "reliable" gyro timestamps, per
     *   spec section 5.
     * UNITS: milliseconds.
     * ORIGIN: Provisional. Was a placeholder pending real data; V0.3 now
     *   supplies real measured jitter, but this threshold value itself is
     *   still a first-principles estimate, not yet cross-validated across
     *   multiple physical devices.
     * STATUS: APPLIED (V0.3) — CapabilityEngine now reports a
     *   low/high verdict against this threshold in its reasoning trail
     *   whenever a SensorQualitySnapshot is supplied. It does NOT yet
     *   gate the returned CapabilityLevel — see CapabilityEngine kdoc for
     *   why Advanced+ still requires V0.4/V0.7 as well.
     */
    const val MAX_GYRO_TIMESTAMP_JITTER_MS_FOR_ADVANCED: Double = 0.5

    /**
     * MEANING: Max allowed stationary gyro standard deviation (noise floor)
     *   for "low noise" classification.
     * UNITS: rad/s.
     * ORIGIN: Provisional, consistent with typical consumer MEMS gyro noise
     *   density specs (roughly 0.01-0.05 dps/sqrt(Hz)-class parts). Needs
     *   device-specific validation across more than one physical unit.
     * STATUS: APPLIED (V0.3) — same caveat as
     *   MAX_GYRO_TIMESTAMP_JITTER_MS_FOR_ADVANCED above: informs the
     *   reasoning trail, does not yet gate CapabilityLevel by itself.
     */
    const val MAX_GYRO_STATIONARY_STD_DEV_RAD_S: Double = 0.01

    // -----------------------------------------------------------------
    // DYNAMIC RESPONSE CROSS-CHECK (measured) — APPLIED as of V0.3.
    // -----------------------------------------------------------------

    /**
     * MEANING: minimum normalized cross-correlation coefficient between
     *   the gyroscope's angular-velocity magnitude and the
     *   rotation-vector-derived angular-velocity magnitude, measured
     *   during a deliberate motion ("flick") phase, above which the two
     *   signals are considered to be describing "the same physical
     *   motion" with reasonable fidelity.
     * UNITS: dimensionless, range [-1, 1] (Pearson correlation).
     * ORIGIN: Provisional. 0.8 is a conservative signal-processing rule
     *   of thumb for "clearly related signals"; not yet validated against
     *   a labeled dataset of known-real vs. known-synthetic gyroscopes.
     * INTERPRETATION CAVEAT: high correlation is expected for BOTH a
     *   high-quality real gyro (which rotation-vector fusion leans on
     *   heavily as its primary input) and a synthesized gyro (which may
     *   literally BE a smoothed derivative of that same fusion pipeline).
     *   This threshold alone does not distinguish those two cases — see
     *   SensorQualityAnalyzer.analyzeDynamicResponse kdoc.
     * STATUS: APPLIED (V0.3) as an INFORMATIONAL flag in the reasoning
     *   trail only, not as a pass/fail gate on CapabilityLevel.
     */
    const val MIN_DYNAMIC_CORRELATION_FOR_CONSISTENT_SIGNAL: Double = 0.8

    /**
     * MEANING: absolute lag (regardless of sign) between the gyroscope
     *   signal and the rotation-vector-derived angular velocity, above
     *   which the gyroscope is flagged as showing SIGNIFICANT latency
     *   relative to the OS's own fused orientation estimate — a concern
     *   for real-time EIS, where the entire point of using a gyro is to
     *   react faster than a fusion filter allows.
     * UNITS: milliseconds.
     * ORIGIN: Provisional. One frame at 30fps is ~33ms; lag much larger
     *   than that would already erode a meaningful fraction of any
     *   stabilization benefit before the pipeline even exists.
     * STATUS: APPLIED (V0.3) as an informational warning in the reasoning
     *   trail; not yet tied to a specific capability-level gate.
     */
    const val MAX_DYNAMIC_LAG_MS_BEFORE_WARNING: Double = 20.0

    // -----------------------------------------------------------------
    // CAMERA STREAM (measured) — NOT YET APPLIED. Defined for V0.4.
    // -----------------------------------------------------------------

    /**
     * MEANING: Minimum sustained camera FPS (MEASURED, not requested)
     *   required for Basic EIS to be worth enabling — below this, dropped
     *   and irregular frames dominate the visible result more than the
     *   stabilization stage can fix.
     * UNITS: fps.
     * ORIGIN: Provisional; a commonly cited floor for a fluid preview.
     * REQUIRES V0.4. NOT YET APPLIED.
     */
    const val MIN_MEASURED_CAMERA_FPS_FOR_BASIC: Double = 24.0

    // -----------------------------------------------------------------
    // SYNCHRONIZATION (measured) — NOT YET APPLIED. Defined for V0.7.
    // -----------------------------------------------------------------

    /**
     * MEANING: Maximum acceptable residual synchronization error between
     *   the gyroscope and camera-frame timelines after offset estimation,
     *   above which interpolated orientation cannot be trusted.
     * UNITS: milliseconds.
     * ORIGIN: Provisional; roughly one gyro sample period at ~200Hz.
     * REQUIRES V0.7. NOT YET APPLIED.
     */
    const val MAX_SYNC_RESIDUAL_ERROR_MS: Double = 5.0

    // -----------------------------------------------------------------
    // PROCESSING
    // -----------------------------------------------------------------

    /**
     * MEANING: Minimum CPU core count below which real-time EIS is not
     *   attempted at all, regardless of sensor quality, because the rest of
     *   the pipeline (decode/synchronize/filter/encode) is unlikely to keep
     *   up on the UI-adjacent threads spec section 28 requires.
     * UNITS: cores.
     * ORIGIN: Engineering estimate — a 4-core device with any working GPU
     *   can usually keep a 1080p30 EIS pipeline off the UI thread.
     * LOWER EFFECT: More low-end devices attempt EIS and may stutter.
     * HIGHER EFFECT: More capable-but-modest devices are excluded even
     *   though per-core speed (not counted here) might compensate.
     * DEVICE-DEPENDENT: Weakly — core *speed* matters more than core
     *   *count*, but count is the only thing available without a
     *   performance benchmark, which does not exist yet.
     * TUNED: NOT experimentally tuned; will be refined once a real
     *   Processing/Performance Test stage exists.
     * APPLIED at V0.2 as a coarse pre-check.
     */
    const val MIN_CPU_CORES_FOR_BASIC: Int = 4

    /**
     * MEANING: Documents that a successful [com.eiscamera.processing.GpuInfoProbe]
     *   query is treated as a prerequisite signal for GPU-accelerated EIS
     *   stages (roadmap V1.1), NOT as evidence of any specific stabilization
     *   level by itself — GPU shader throughput is unmeasured at V0.2.
     * APPLIED: Reserved for V1.1 gating logic.
     */
    const val GPU_PROBE_SUCCESS_REQUIRED_FOR_ADVANCED: Boolean = true
}
