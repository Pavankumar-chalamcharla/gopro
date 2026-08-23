package com.eiscamera.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.eiscamera.logging.EisLog
import com.eiscamera.orientation.GyroIntegrator
import com.eiscamera.orientation.QuaternionMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Live-updating orientation state for display. Quaternions are internal
 * state only (not exposed) — what the UI actually needs is the derived
 * numbers below. Angle is radians.
 */
data class LiveOrientationState(
    val running: Boolean = false,
    val sampleCount: Long = 0,
    val gyroRateHz: Double? = null,
    val compensationAngleRad: Double = 0.0,
    val biasCorrectionApplied: Boolean = false,
)

/**
 * V1.0b: continuously integrates raw gyroscope samples into an orientation
 * estimate (V0.8's GyroIntegrator) and runs that through V0.9's low-pass
 * filter, in real time, for as long as [start] is active — the first
 * place in the project either of those run outside a bounded test window.
 *
 * Deliberately does NOT touch the camera image. The only thing this stage
 * needs to prove is that the sensor math keeps up with real time without
 * stalling anything else running alongside it (the live camera preview).
 * Applying the resulting compensation to the actual image is V1.0c.
 *
 * THREADING: sensor callbacks run on a dedicated HandlerThread — never the
 * main thread — so continuous ~200Hz quaternion math never competes with
 * UI rendering or the camera's own callbacks (spec section 28). [state]
 * is a StateFlow, safe to update from this background thread and observe
 * from Compose on the main thread with no manual hand-off.
 *
 * CUTOFF FREQUENCY: defaults to 2.0 Hz, the same value already verified
 * against the theoretical single-pole response in
 * OrientationSmoothingFilterTest. This is a STARTING default, not yet
 * tuned against real hand-shake/pan data captured on this device — spec
 * section 33 requires marking that distinction rather than presenting an
 * untuned constant as if it were already validated.
 *
 * BIAS CORRECTION: uses whatever stationary bias V0.3 last measured and
 * saved to the device profile, exactly like V0.8's OrientationDriftAnalyzer
 * does. If no V0.3 result has been saved yet, runs uncorrected rather
 * than silently assuming zero bias is meaningful — [LiveOrientationState
 * .biasCorrectionApplied] reports which case is active so the UI can say
 * so honestly.
 *
 * CROSS-THREAD READS (added V1.0c-2): [currentCorrectionQuaternion] is
 * read from the GL thread every rendered frame, a completely different
 * thread from the one updating qRaw/qSmooth here. Both are published
 * together as one immutable [OrientationSnapshot] via an AtomicReference
 * so a reader always sees a matched raw/smooth pair from the same
 * instant — never raw from one sample paired with smooth from a
 * different one, which a pair of plain fields read across threads
 * without synchronization could not guarantee.
 */
class LiveOrientationPipeline(
    context: Context,
    private val biasRadS: DoubleArray?,
    private val cutoffHz: Double = DEFAULT_CUTOFF_HZ,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var thread: HandlerThread? = null

    private data class OrientationSnapshot(val qRaw: DoubleArray, val qSmooth: DoubleArray)
    private val snapshotRef = AtomicReference(OrientationSnapshot(IDENTITY.copyOf(), IDENTITY.copyOf()))

    private var lastTimestampNs: Long? = null
    private var sampleCount = 0L
    private var rateEstimateHz: Double? = null

    private val _state = MutableStateFlow(LiveOrientationState())
    val state: StateFlow<LiveOrientationState> = _state.asStateFlow()

    /**
     * The rotation to apply to cancel the current shake: "how to get from
     * the actual (raw) orientation to the desired (smoothed) one." Safe to
     * call from any thread — this is the GL thread's read path, added for
     * V1.0c-2. Returns identity (no correction) before the first two gyro
     * samples have arrived, matching [LiveOrientationState]'s own
     * before-running default.
     *
     * DIRECTION: verified numerically before this was written — for a
     * camera that yawed +3deg off the smoothed reference, this returns
     * a ~-3deg correction, i.e. the rotation that undoes the shake.
     * conjugate(qRaw) is q_raw's inverse; composing with qSmooth on the
     * right expresses "first undo raw, then apply smooth," matching the
     * `new = old (x) delta` convention GyroIntegrator itself already uses.
     */
    fun currentCorrectionQuaternion(): DoubleArray {
        val snap = snapshotRef.get()
        return QuaternionMath.hamiltonProduct(QuaternionMath.conjugate(snap.qRaw), snap.qSmooth)
    }

    /** Starts continuous integration. No-op if already running. */
    fun start() {
        if (thread != null) return

        snapshotRef.set(OrientationSnapshot(IDENTITY.copyOf(), IDENTITY.copyOf()))
        lastTimestampNs = null
        sampleCount = 0L
        rateEstimateHz = null

        val sensor = gyroSensor
        if (sensor == null) {
            EisLog.w(EisLog.Tag.SENSOR, "No gyroscope available; live orientation pipeline cannot start")
            _state.value = LiveOrientationState(running = false)
            return
        }

        val t = HandlerThread("LiveOrientation").apply { start() }
        thread = t
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
        _state.value = LiveOrientationState(running = true, biasCorrectionApplied = biasRadS != null)
        EisLog.i(EisLog.Tag.SENSOR, "Live orientation pipeline started (bias correction=${biasRadS != null})")
    }

    /** Stops integration and releases the sensor listener/thread. Safe to call even if not running. */
    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        _state.value = LiveOrientationState(running = false)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val prevTs = lastTimestampNs
        lastTimestampNs = event.timestamp
        if (prevTs == null) return // first sample only establishes the time base; nothing to integrate yet
        val dtS = (event.timestamp - prevTs) / 1_000_000_000.0
        if (dtS <= 0) return // guards against any out-of-order timestamp

        var wx = event.values[0].toDouble()
        var wy = event.values[1].toDouble()
        var wz = event.values[2].toDouble()
        val bias = biasRadS
        if (bias != null) {
            wx -= bias[0]; wy -= bias[1]; wz -= bias[2]
        }

        val snap = snapshotRef.get()
        val newQRaw = GyroIntegrator.integrateStep(snap.qRaw, doubleArrayOf(wx, wy, wz), dtS)
        val alpha = OrientationSmoothingFilter.alphaForCutoff(cutoffHz, dtS)
        val newQSmooth = OrientationSmoothingFilter.step(snap.qSmooth, newQRaw, alpha)
        snapshotRef.set(OrientationSnapshot(newQRaw, newQSmooth))
        val compensationRad = QuaternionMath.angleBetween(newQSmooth, newQRaw)

        sampleCount++
        // Exponential smoothing on the rate estimate itself (alpha=0.1) purely so
        // the displayed Hz number doesn't jitter sample-to-sample from timestamp
        // noise — this is a display convenience, unrelated to the orientation
        // filter's own alpha/cutoff above.
        val instantaneousHz = 1.0 / dtS
        rateEstimateHz = rateEstimateHz?.let { it * 0.9 + instantaneousHz * 0.1 } ?: instantaneousHz

        _state.value = LiveOrientationState(
            running = true,
            sampleCount = sampleCount,
            gyroRateHz = rateEstimateHz,
            compensationAngleRad = compensationRad,
            biasCorrectionApplied = bias != null,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val DEFAULT_CUTOFF_HZ = 2.0
        private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    }
}
