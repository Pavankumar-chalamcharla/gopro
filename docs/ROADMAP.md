# Roadmap

Tracks the incremental development sequence this project follows (never
skip directly to a "finished-looking" system — each stage must be testable
before the next one starts).

| Stage | Description | Status |
|---|---|---|
| V0.1 | Project skeleton | **Done** — Gradle project, manifest, empty-ish MainActivity |
| V0.2 | Device capability scanner | **Done** — sensors + cameras + processing/GPU static inventory, DeviceProfile persistence, conservative CapabilityEngine, diagnostic UI |
| V0.3 | Sensor diagnostics | **Done** — stationary noise/jitter/bias measurement, plus a dynamic-response cross-check against the rotation-vector sensor (gyro-vs-fusion cross-correlation) to help detect synthesized/fused "gyroscopes" |
| V0.4 | Camera diagnostics | **Done** — opens each camera (real Camera2 capture session, not just static characteristics), measures actual sustained FPS, frame-interval jitter, and a likely-dropped-frame heuristic, independently per camera id |
| V0.5 | Gyro recorder | Superseded by V0.3's collector + V0.7's concurrent collector — no separate stage needed |
| V0.6 | Camera recorder | Superseded by V0.4's real capture session + V0.7's concurrent collector — no separate stage needed |
| V0.7 | Camera/gyro synchronization | **Done** — empirical clock-offset estimation (gyro angular velocity vs. camera motion-energy cross-correlation, robust to unrelated clock epochs) and SLERP-based orientation interpolation at arbitrary camera timestamps |
| V0.8 | Orientation estimation | **Done** — quaternion exponential-map integration of raw angular velocity, plus an empirical on-device drift measurement against the rotation-vector reference (with automatic bias correction from V0.3 when available) |
| V0.9 | Motion filtering | **Done** — SLERP-based single-pole low-pass filter on the orientation stream, separating intentional motion (preserved) from hand-shake (flagged as compensation to cancel later); frequency response verified numerically against the theoretical single-pole formula before implementation |
| V1.0 | Basic real-time EIS | In progress — split into V1.0a/b/c/d sub-stages (see below); **V1.0a, V1.0b, and V1.0c-1 done** |
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

## What schema/architecture changed in V0.4

- `DeviceProfile.SCHEMA_VERSION` bumped 2 -> 3, adding
  `cameraQuality: List<CameraStreamQualitySnapshot>` (a list, not a single
  value — each camera is tested and reported independently).
- `CapabilityThresholds.MIN_MEASURED_CAMERA_FPS_FOR_BASIC` moved from
  "documented but unused" to "applied." Two new thresholds
  (`MAX_CAMERA_FRAME_JITTER_MS_FOR_ADVANCED`,
  `MAX_LIKELY_DROPPED_FRAME_RATIO`) were added.
- New `camera/CameraStreamQualityAnalyzer.kt` (pure math, unit tested),
  `camera/CameraStreamQualityCollector.kt` (real Camera2 capture session —
  opens the camera, runs a repeating request, records CaptureResult timing
  metadata, tears everything down safely), and `ui/CameraQualityScreen.kt`
  (runtime CAMERA permission request + camera picker + countdown + results).
- `AndroidManifest.xml` now declares `android.permission.CAMERA`, requested
  at runtime by that screen — the first dangerous permission this project
  needs.

## What V0.7 added

- `motion/TimeSeriesCorrelation.kt` — the interpolation/cross-correlation
  math from V0.3 was pulled out into a shared, reused module rather than
  duplicated (see the V0.3 quaternion double-cover bug entry above for why
  duplicated math is worth avoiding).
- `synchronization/SyncAnalyzer.kt` — estimates the gyro<->camera clock
  offset by cross-correlating gyro angular velocity against a camera
  frame-to-frame "motion energy" signal, deliberately reframed to each
  series' own relative start time so it stays correct even when the two
  clocks share no epoch at all (verified numerically against synthetic
  data with an artificial ~488-second epoch difference before being coded
  in Kotlin — see SyncAnalyzerTest). Also provides SLERP-based orientation
  interpolation between rotation-vector samples at an arbitrary camera
  timestamp — the actual "estimate orientation at arbitrary camera
  timestamps" mechanism spec section 7 asks for.
- `synchronization/CameraMotionCollector.kt` — the first collector that
  runs gyro AND camera concurrently, and the first to read actual pixel
  data (Y/luma plane only, coarsely downsampled) to build a motion signal.
- `CameraInfo.timestampSource` (V0.2 addition) — reads
  SENSOR_INFO_TIMESTAMP_SOURCE, so the app knows definitively whether a
  camera's clock is platform-guaranteed to match the gyro's or not.
- `DeviceProfile.SCHEMA_VERSION` bumped 3 -> 4, adding `syncResult`.
- **CapabilityEngine can now return LEVEL_2_ADVANCED** — the first time
  any level above Basic has been reachable. This requires ALL THREE of
  V0.3, V0.4, and V0.7 to be present AND every one of their thresholds to
  pass independently (jitter, noise, camera FPS/jitter, sync correlation/
  offset) — no partial credit, no averaging. See `advancedEligibility()`
  in CapabilityEngine.kt for the exact, documented gate, and
  CapabilityEngineTest for cases proving a single failing measurement
  still keeps the result at Basic.

## What V0.8 added

- `orientation/GyroIntegrator.kt` — quaternion exponential-map integration
  of angular velocity (verified numerically to be EXACT for constant
  angular velocity, at any step count, before being written in Kotlin —
  see the project's math verification notes and GyroIntegratorTest).
- `orientation/OrientationDriftAnalyzer.kt` — chains the integrator across
  a real gyro sample stream and empirically compares the result against
  the rotation-vector reference orientation at the end of the window,
  reporting a genuine "drift over N seconds" number for THIS device's
  actual gyro. Automatically applies bias correction using V0.3's already-
  measured per-axis stationary bias, if available.
- `SensorQualitySnapshot` gained `biasXRadS`/`biasYRadS`/`biasZRadS` —
  the per-axis bias vector was already computed in V0.3's analyzer but
  only the scalar MAGNITUDE was persisted; direction is required to
  actually subtract a bias from a 3-vector, so V0.8 needed the full
  per-axis values.
- `DeviceProfile.SCHEMA_VERSION` bumped 4 -> 5, adding `orientationDrift`.
- `ui/OrientationDriftScreen.kt` reuses V0.3's `SensorQualityCollector`
  as-is (it already gathers gyro + rotation-vector concurrently) rather
  than duplicating a collector.
- CapabilityEngine gained an informational drift reasoning line, but
  **orientation drift deliberately does NOT feed the Advanced-level
  gate** — that gate is scoped around the three measurements (V0.3/V0.4/
  V0.7) that answer "can this device do gyro-based EIS at all"; drift is
  a property of the integration pipeline built on top of that answer, not
  a new input to it. See CapabilityEngine kdoc.

## What V0.9 added

- `orientation/QuaternionMath.kt` — Hamilton product, normalize, angle-
  between, and SLERP consolidated into one shared module. These existed
  as separate copies in `GyroIntegrator` and `SyncAnalyzer`; both were
  refactored to delegate here (public APIs and behavior unchanged — their
  existing tests continue to cover this code, just through a different
  entry point) rather than adding a third copy for this filter to use.
- `motion/OrientationSmoothingFilter.kt` — the actual V0.9 deliverable: a
  SLERP-based single-pole low-pass filter, with a documented, verified
  α↔cutoff-frequency relationship (`alphaForCutoff` / `cutoffForAlpha`),
  a single-step function, and a `filterStream` convenience that also
  reports the per-sample "compensation angle" — the gap between raw and
  smoothed orientation, i.e. what a future stabilization stage would need
  to cancel.
- Deliberately the simplest correct option (a single-pole low-pass), not
  a One Euro Filter or other adaptive scheme — spec section 12 warns
  against reaching for sophistication without evidence it's needed.
- No on-device UI this time, on purpose: unlike V0.3/V0.4/V0.7/V0.8, this
  isn't a one-shot diagnostic test — it's a continuous filter meant to be
  wired into the live pipeline at V1.0. Its correctness was established
  entirely through frequency-response testing against synthetic data
  (verified in Python first, then reproduced as permanent Kotlin
  regression tests), matching spec section 29's explicit requirement for
  filtering components ("known input signal -> expected frequency
  response").

## V1.0 sub-stages

V1.0 ("Basic real-time EIS") is a meaningfully bigger jump than any prior
version — it's the first continuously-running pipeline instead of a
bounded test window, which changes the threading and lifecycle questions
substantially (spec section 28). Rather than deliver it as one large
change, it's split into four small, independently-testable sub-stages:

- **V1.0a — continuous GPU preview, unstabilized (DONE).** Prove the
  basic plumbing: camera opens, stays open, renders continuously,
  releases cleanly on exit. `CameraPreviewViewModel` manages a
  **repeating** Camera2 capture session (`setRepeatingRequest`, not the
  bounded single-capture loop V0.4/V0.7/V0.8 used) targeting a
  `TextureView`'s own `SurfaceTexture`. Deliberately NOT GL/shader-based
  yet: TextureView displays the camera feed itself with no custom
  rendering code needed, and introducing a custom OES-texture/shader
  pipeline isn't justified until V1.0c actually needs to warp the image
  — spec section 12's "don't add sophistication without evidence"
  applies to architecture choices too, not just algorithms. Wired into
  `MainActivity` as a fifth screen alongside the four existing tests.
  Known limitation, stated rather than silently ignored: camera release
  is currently tied to Compose leaving the screen (`DisposableEffect`),
  not to the Activity's onPause/onStop — backgrounding the whole app
  won't yet release the camera. Worth closing before V1.0 is "done,"
  not before V1.0a specifically. Confirmed stable on-device over long
  duration.
- **V1.0b — live orientation pipeline running alongside it (DONE).**
  `motion/LiveOrientationPipeline.kt` continuously integrates raw gyro
  samples (V0.8's GyroIntegrator, bias-corrected using whatever V0.3
  last measured) and runs the result through V0.9's low-pass filter
  (2.0Hz default cutoff — a starting point, not yet tuned against real
  hand-shake/pan data), in real time, on its own dedicated
  HandlerThread — never the main thread, so continuous ~200Hz
  quaternion math can't compete with UI rendering or the camera's own
  callbacks. `CameraPreviewViewModel` starts/stops it alongside the
  camera session; a small debug overlay on the live preview shows gyro
  rate, compensation angle, sample count, and whether bias correction
  is actually active — the numbers that prove the sensor side keeps up
  in real time, not just a smooth-looking image. Still no change to the
  image itself.
- **V1.0c — apply the compensation transform.** Split further into two
  sub-steps once it became clear this was the largest architectural jump
  of the four:
  - **V1.0c-1 — GL rendering plumbing (DONE).** `rendering/CameraGlRenderer.kt`
    replaces V1.0a/b's `TextureView` passthrough with a real GPU render
    path: the camera feed drawn as an external OES texture through a
    custom shader, via `GLSurfaceView` (which owns EGL context/thread
    setup — hand-rolling raw EGL was considered and rejected as
    unjustified complexity for what's needed here). `compensationMatrix`
    is a 3x3 identity matrix at this stage, applied in the shader but
    doing nothing — the picture should look and behave identically to
    V1.0a/b, pixel for pixel. The point of this step is entirely
    architectural: prove the OES-texture/shader plumbing itself is
    correct in isolation, so V1.0c-2's actual transform math is the only
    new variable when it's added, rather than debugging GL setup and
    stabilization math at the same time.
    **Fixed after first on-device test:** a hand-created `SurfaceTexture`
    defaults to a 1x1 pixel buffer until told otherwise, unlike
    `TextureView` which sets this automatically to match its own
    on-screen size — this had gone unset, so Camera2 fell back to a tiny
    compatible resolution that then got stretched across the full
    screen, a real and visible quality drop, not an inherent cost of
    moving to GL. `CameraSessionUtils.choosePreviewSize` (~1280x720,
    picked from the sizes Camera2 actually reports as valid for
    SurfaceTexture output) now sets the buffer size explicitly before
    the capture session is configured.
  - **V1.0c-2 — the actual transform (not yet started).** Feed
    `setCompensationMatrix` a real per-frame rotation + translation
    derived from V1.0b's live orientation compensation, plus a matching
    crop so the shifted content doesn't reveal empty edges. This is the
    step where the picture will actually visibly change.
- **V1.0d — measure it while it runs.** The spec section 19 debug
  overlay (FPS, gyro rate, EIS latency, crop %) so the numbers, not just
  the look, confirm it's working in real time.

## What's next (V1.0c-2)

With the GL plumbing proven in V1.0c-1, V1.0c-2 is the step where the
picture actually starts moving: derive a 2D rotation+translation from the
gap between V1.0b's raw and smoothed orientation (roll maps directly to
image rotation; pitch/yaw map to translation via the standard small-angle
pinhole approximation, using this camera's measured focal length and
sensor size from V0.2), verify that projection math numerically before
touching Kotlin, then feed it into `CameraGlRenderer.setCompensationMatrix`
each frame alongside a matching crop.
