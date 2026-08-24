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
| V1.0 | Basic real-time EIS | **Done** — split into V1.0a/b/c/d sub-stages (see below), all complete: continuous GPU preview, live orientation pipeline, real-time gyro-based stabilization transform, and measured performance numbers |
| V1.1 | Save stabilized video to file | **Done** — real, open-ended recording of the actual stabilized camera feed to a discoverable MP4 file |
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
  - **V1.0c-2 — the actual transform (DONE).** The first change in this
    project that actually alters the image. `stabilization/
    CompensationTransform.kt` converts the rotation gap between
    V1.0b's raw and smoothed orientation into a 2D texture-coordinate
    transform: roll maps directly to image rotation; pitch/yaw map to
    translation via the standard small-angle pinhole approximation
    (using this camera's actual V0.2-measured focal length and sensor
    size — no invented calibration). A fixed 10% crop keeps typical
    shifts safely inside the source texture instead of visibly
    smearing the clamped edge. Every piece of this — the correction
    quaternion's direction, the small-angle extraction error (~0.0001°
    at 2°), the composed matrix's behavior in isolation (identity case,
    crop-only, rotation-about-center) — was verified numerically in
    Python before any Kotlin was written, then reproduced as permanent
    unit tests in `CompensationTransformTest.kt`.
    `LiveOrientationPipeline` gained `currentCorrectionQuaternion()`,
    safe to call from the GL thread even though the pipeline updates
    from its own separate sensor thread — both quaternions now publish
    together as one immutable snapshot via `AtomicReference` so a
    reader never sees a raw/smooth pair from two different instants.
    **Known, expected uncertainty stated plainly rather than hidden:**
    the correction *magnitude* is verified math; which screen direction
    it visually corresponds to on this specific device can only be
    confirmed by watching it run. If it looks backwards on-device,
    that's a one-line sign flip in `CompensationTransform.compose`, not
    a deeper problem — a normal first-pass step for this class of
    feature, not evidence something was done wrong.
    **Fixed after real-device testing reported two specific, useful
    symptoms:** "wobbling on small shakes" and "no correction on large
    shakes." Both root-caused with actual simulation numbers before
    fixing (spec section 40), not guessed at — see
    CompensationTransform's kdoc for the full reasoning. Wobble: this
    device's synthesized gyro has known, already-measured integration
    drift (V0.3/V0.8) that a fixed low-pass filter can't distinguish
    from genuine slow motion, so the smoothed reference itself wanders;
    a deadband (ignore below 0.15°, ramp to full strength by 0.6°)
    stops the filter from chasing that noise. No correction on big
    shakes: the original 10% crop margin only covered ~±4° before
    sampling ran outside the texture — increased to 20% (~±8°) with
    correction now clamped explicitly and gracefully at that boundary,
    rather than left to whatever raw GL edge-clamping happened to
    produce.
    **Two more real bugs found from a second round of on-device
    testing, both confirmed numerically before fixing:**
    1. Persistent corner/edge blur, even with no shake at all — the
       crop's scale factor was inverted (`1/(1-cropMargin)` instead of
       `1-cropMargin`), which zooms sampling OUT rather than in, the
       opposite of a crop. Verified: at 20% margin the old formula put
       every frame corner at (1.125, 1.125) — already outside the valid
       [0,1] texture range at baseline, guaranteeing edge-clamp smear
       on every frame regardless of correction. The ORIGINAL
       verification test for this had the same wrong expectation baked
       in ("edge should sample beyond 1.0, correctly cropped out") — a
       real reminder that passing a test only proves consistency with
       what the test asserts, not correctness, if the assertion itself
       encodes the wrong mental model. Both the formula and the test
       are now corrected together.
    2. The live preview only filled roughly half the screen height,
       with the bottom status row visibly squeezed into a sliver width
       (its "Switch Camera" text was wrapping one letter per line —
       the telltale sign of a badly-constrained width). `GLSurfaceView`
       is a `SurfaceView` subclass, a class with known, longstanding
       layout-measurement quirks when embedded without explicit size
       hints, since its content renders through a separate compositor
       layer rather than the normal View drawing path. Now given
       explicit `MATCH_PARENT` LayoutParams rather than relying on
       Compose's `AndroidView` default sizing inference.
- **V1.0d — measure it while it runs (DONE).** `CameraGlRenderer` now
  tracks `RenderStats` — a rolling FPS estimate from actual
  onDrawFrame-to-onDrawFrame wall-clock time, and per-frame CPU-side
  draw time — stated explicitly as CPU submission time, not true GPU
  execution time (which needs the EXT_disjoint_timer_query extension,
  not assumed available and not used here — spec section 41's
  MEASURED/ESTIMATED distinction). The debug overlay now shows this
  alongside gyro rate, compensation angle, and the actual crop
  percentage in effect, so the numbers — not just how it looks —
  confirm this is running in real time. True GPU timing and camera FPS
  (vs. render FPS, which can legitimately differ) remain open for a
  more careful future pass.
  **Also folded in during this same delivery, at the user's request
  rather than as a separate round-trip:** the preview layout was
  restructured from a `Column`/`weight(1f)`/`Row` split to a single
  full-screen `Box` with controls overlaid directly on the video —
  the `Row`-based approach was still leaving a large unfilled gap
  below the video with the bottom controls squeezed into a sliver
  width, even after the `GLSurfaceView` LayoutParams fix above. This
  is also simply the standard pattern real camera apps use.

## V1.1 sub-stages

Every V1.0 sub-stage is done, so V1.0 as a whole — **Basic real-time
EIS** — is complete: a continuously-running, gyro-stabilized live
camera preview with the numbers to back up that it's real. V1.1 is
about actually saving that stabilized output to a video file — genuinely
comparable in complexity to V1.0c's GL work (which needed several real-
device rounds to get right), so it gets the same sub-staged treatment
rather than one large, blind attempt:

- **V1.1a — recording state scaffolding (DONE).** A real, working
  Record/Stop button and elapsed-time counter
  (`RecordingUiState`/`CameraPreviewViewModel.startRecording`/
  `stopRecording`), with **no video actually saved yet** — stated
  explicitly in the UI itself (`Stopped.note`), not implied to do more
  than it does (spec section 42). This proves the state-machine and UI
  side before the substantially harder next piece.
  **Also fixed in this same delivery, at the user's request:** the
  "Switch Camera" button was still wrapping one letter per line even
  after the earlier full-screen restructure — a different, simpler bug
  than the GLSurfaceView layout issue fixed before it. The status text
  string was simply long enough to claim nearly the whole row before
  Compose got to laying out the button. Fix: give the status `Text` an
  explicit `weight(1f)` (so the button, unweighted, is always measured
  at its natural size first and the text truncates with an ellipsis
  instead of squeezing everything else) and shortened the string for
  extra margin.
- **V1.1b — the actual encoder.** Split further, same reasoning as
  V1.0c: this is genuinely comparable in complexity to the GL renderer
  work, which needed several real-device rounds.
  - **V1.1b-1 — prove the mechanism in isolation (DONE).**
    `recording/EncoderCapabilities.kt` queries this device's actual
    supported encoder, resolution range, and alignment requirements
    (never assumes H.264 "just works" at an arbitrary size — spec
    section 18). `recording/TestPatternRecorder.kt` configures a real
    `MediaCodec` encoder in Surface-input mode, sets up EGL BY HAND for
    the first time in this project (there's no `GLSurfaceView`
    equivalent for an encoder's input surface — GLSurfaceView had
    always handled this automatically before), renders a slowly-
    cycling solid color into it, and muxes the output into a real MP4
    via `MediaMuxer` — completely isolated from the real camera feed on
    purpose, so if something's wrong, it's unambiguously an encoder/EGL
    problem and not a camera or stabilization one. Wired to the
    existing Record button from V1.1a.
    **Two real bugs found and fixed from the first real-device test:**
    1. The clip finished in ~1 second instead of the requested 5 —
       the frame loop had no pacing to real time at all (just
       glClear+eglSwapBuffers+a poll, as fast as the CPU/GPU could go),
       so the encoder's implicit presentation timestamps reflected a
       tiny real elapsed time regardless of the intended duration.
       Fixed with explicit, evenly-spaced presentation timestamps via
       `eglPresentationTimeANDROID` plus real-time `Thread.sleep`
       pacing, so both the encoded video's duration and the actual
       wall-clock recording time now match what was requested.
    2. The reported file path genuinely existed but was practically
       invisible — it was the app's PRIVATE external-files directory,
       which modern file manager apps commonly hide from browsing (a
       Scoped Storage restriction most users would have no way to know
       about or work around). Fixed by switching to `MediaStore`
       (`recording/MediaStoreVideoOutput.kt`), saving into the public
       Movies collection instead, where Gallery and file apps show it
       normally. `TestPatternRecorder` now takes a `FileDescriptor`
       rather than a `File` path so it doesn't need to know or care
       which storage mechanism the caller used.
    **Known, stated limitation, still true:** the fixed 5-second clip
    isn't cancellation-aware mid-recording yet, so the button disables
    itself during that window rather than offering a "Stop" that
    wouldn't actually shorten it — real open-ended start/stop control
    is V1.1b-2's job, once this is redesigned around
    the continuously-running camera feed anyway.
  - **V1.1b-2 — feed it the real stabilized frames (DONE).**
    `CameraGlRenderer` now draws each frame's already-computed
    stabilization transform to a SECOND target — a `MediaCodec`
    encoder's input surface, via `beginRecording`/`endRecording` — in
    addition to the screen, immediately after the normal screen draw.
    The shared-context technique (querying `EGL14
    .eglGetCurrentContext/eglGetCurrentDisplay` from inside
    `onDrawFrame`, since GLSurfaceView never exposes these directly, but
    a context is guaranteed current whenever that callback runs) means
    the second surface reuses the exact same OES camera texture already
    uploaded for the screen draw that frame — no separate upload or
    copy. `recording/EncoderSession.kt` generalizes V1.1b-1's proven
    encoder/muxer logic into an open-ended session instead of a fixed
    duration. `CameraPreviewViewModel` gained `registerRenderer`/
    `unregisterRenderer` so recording can reach the live renderer's GL
    thread via `GLSurfaceView.queueEvent`, and `stopRecording` uses
    `suspendCancellableCoroutine` to genuinely WAIT for
    `endRecording` to finish running on the GL thread before releasing
    the encoder's surface — release ordering matters here (releasing
    while the renderer still holds an EGLSurface wrapping it is
    unsafe), not something `queueEvent` alone confirms. The Record
    button is genuinely open-ended now: Stop actually stops it, saving
    a real MP4 of the actual stabilized camera feed to Movies/EisCamera
    via the same MediaStore path V1.1b-1's fix already established.

## What's next

Every planned V1.0 and V1.1 sub-stage is done: continuous GPU preview,
live orientation pipeline, real-time gyro-based stabilization, measured
performance numbers, and now actually saving that stabilized output to
a real, playable, discoverable video file. This is a natural point to
pause on new features and spend time tuning the constants that were
always marked as reasoned-but-unvalidated starting points against real
recorded footage now that it exists — the deadband/crop-margin/cutoff
values in `stabilization/CompensationTransform.kt`, primarily — before
reaching for the next roadmap stage (V1.2 adaptive crop, V1.3 lens
profiles, V1.4 rolling shutter) on top of an untuned baseline.
