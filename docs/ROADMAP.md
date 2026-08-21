# Roadmap

Tracks the incremental development sequence this project follows (never
skip directly to a "finished-looking" system — each stage must be testable
before the next one starts).

| Stage | Description | Status |
|---|---|---|
| V0.1 | Project skeleton | **Done** — Gradle project, manifest, empty-ish MainActivity |
| V0.2 | Device capability scanner | **Done** — sensors + cameras + processing/GPU static inventory, DeviceProfile persistence, conservative CapabilityEngine, diagnostic UI |
| V0.3 | Sensor diagnostics | **Done** — stationary noise/jitter/bias measurement, plus a dynamic-response cross-check against the rotation-vector sensor (gyro-vs-fusion cross-correlation) to help detect synthesized/fused "gyroscopes" |
| V0.4 | Camera diagnostics | Not started — open each camera, measure real sustained FPS, frame-timestamp stability, dropped frames, independently per camera id |
| V0.5 | Gyro recorder | Not started — continuous background-thread gyro capture with a ring buffer, feeding V0.7 |
| V0.6 | Camera recorder | Not started — CameraX or raw Camera2 capture session, live preview, CAMERA runtime permission flow |
| V0.7 | Camera/gyro synchronization | Not started — timestamp domain reconciliation, offset/drift estimation, interpolation at arbitrary camera timestamps |
| V0.8 | Orientation estimation | Not started — quaternion integration of angular velocity |
| V0.9 | Motion filtering | Not started — high-frequency vs. low-frequency motion separation |
| V1.0 | Basic real-time EIS | Not started — first end-to-end stabilized preview |
| V1.1 | GPU EIS | Not started — OpenGL ES/EGL shader-based warp, replacing any CPU path |
| V1.2 | Adaptive crop | Not started |
| V1.3 | Lens profiles | Not started |
| V1.4 | Rolling shutter | Not started |
| V1.5 | Advanced device adaptation | Not started |
| V2.0 | Production-quality universal EIS camera | Not started |

## Why the CapabilityEngine only returns "Basic (provisional)" or "Unsupported" today

`CapabilityEngine` (see `app/src/main/java/com/eiscamera/capability/CapabilityEngine.kt`)
is structurally incapable of returning `LEVEL_2_ADVANCED` or higher right
now — there is no code path that produces that result — even though V0.3
now supplies real measured sensor quality data. Reaching Advanced+ also
requires measured camera stream stability (V0.4) and measured
synchronization quality (V0.7), neither of which exist yet. V0.3 makes the
engine's *reasoning* evidence-based; it deliberately does not yet change
the returned *level*.

## V0.3 real-device finding (OPPO F31 5G / CPH2781)

The first real run on the validation device surfaced something the V0.2
static scan alone couldn't: the device's `Sensor.TYPE_GYROSCOPE` is named
`oem-pseudo-gyro`, vendor `virtual_gyro` — i.e. not a genuine gyroscope
chip, almost certainly a MediaTek sensor-HAL fusion of the real
accelerometer (`sc7a20`, Silan) and magnetometer (`mmc5603`, Memsic). The
V0.3 dynamic-response cross-check (gyro vs. rotation-vector-derived
angular velocity) exists specifically to surface evidence for this kind of
case — see the interpretation caveat in
`SensorQualityAnalyzer.analyzeDynamicResponse` kdoc for what that
cross-check can and cannot prove on its own.

The stationary-phase measurement on real hardware also caught something
the declared numbers alone couldn't: measured rate came back ~199 Hz
against a DECLARED rate of ~100 Hz — a 2x mismatch. That kind of gap
between declared and measured is exactly why V0.2's kdoc always insisted
declared values are provisional; this is the first concrete case of it
mattering.

## V0.3 bug found and fixed via real-device testing: quaternion double cover

Following the project's debugging methodology (spec section 40):

```
Observed:    Dynamic-response test reported a rotation-vector-derived
             peak angular velocity of ~1187 rad/s (≈68,000°/s) — a value
             no phone flick can physically produce.
Hypothesis:  A bug in the quaternion-differencing math, not the sensor.
Measurement: Isolated the exact two-quaternion pair driving the spike;
             reproduced it in a standalone numeric check.
Experiment:  Constructed a synthetic case — a small, genuinely tiny
             rotation (0.10 rad over 10ms, true rate 10 rad/s) whose
             second sample was deliberately sign-flipped (q vs -q, which
             represent the identical physical orientation for any unit
             quaternion — the "double cover" property). The buggy code
             recovered ~618 rad/s from that flipped sample instead of 10.
Root cause:  Android's rotation-vector sensor does not guarantee a
             consistent quaternion sign/hemisphere between consecutive
             samples. The angle-recovery formula used the raw scalar
             (dot-product) term directly; when a sign flip occurred, a
             near-1 dot product became near-(-1), and acos() turned a
             near-zero angle into a near-180° one.
Minimal fix: Take the absolute value of the dot product before acos().
             cos(Δθ/2) = |dot(q1,q2)| is invariant to either input's
             sign, and correctly recovers the shortest relative rotation
             regardless of which hemisphere each sample happened to
             report on.
Regression
  test:      SensorQualityAnalyzerTest — "quaternionAngularVelocityMagnitude
             is immune to a sign-flipped sample" constructs exactly this
             scenario and asserts the small true rate is still recovered.
```

This is a good illustration of why real-device testing matters even for
pure-math code that passed every unit test against synthetic data: the
synthetic test data was constructed from a smooth, continuous formula that
never triggers a sign discontinuity, so it couldn't have caught this on
its own. Real sensor data did.

## What schema/architecture changed in V0.3

- `DeviceProfile.SCHEMA_VERSION` bumped 1 -> 2, adding a nullable
  `sensorQuality: SensorQualitySnapshot?` field. Old cached (v1) profiles
  are automatically discarded and rescanned by the existing
  `DeviceProfileRepository` schema-version check — no migration code
  needed.
- `CapabilityThresholds.MAX_GYRO_TIMESTAMP_JITTER_MS_FOR_ADVANCED` and
  `MAX_GYRO_STATIONARY_STD_DEV_RAD_S` moved from "documented but unused" to
  "applied" (informs `CapabilityEngine` reasoning; does not yet gate the
  returned level). Two new thresholds
  (`MIN_DYNAMIC_CORRELATION_FOR_CONSISTENT_SIGNAL`,
  `MAX_DYNAMIC_LAG_MS_BEFORE_WARNING`) were added for the dynamic test.
- New `sensors/SensorQualityAnalyzer.kt` (pure math, unit tested),
  `sensors/SensorQualityCollector.kt` (Android SensorEventListener
  wrapper), and a new `ui/SensorQualityScreen.kt` two-phase guided flow,
  reachable from the main diagnostic screen.

## What's next (V0.4)

Camera stream quality test: open each camera (not just read static
CameraCharacteristics), measure actual sustained FPS, frame-timestamp
stability, and dropped frames — independently per camera id, per spec
section 6.
