# Roadmap

Tracks the incremental development sequence this project follows (never
skip directly to a "finished-looking" system — each stage must be testable
before the next one starts).

| Stage | Description | Status |
|---|---|---|
| V0.1 | Project skeleton | **Done** — Gradle project, manifest, empty-ish MainActivity |
| V0.2 | Device capability scanner | **Done** — sensors + cameras + processing/GPU static inventory, DeviceProfile persistence, conservative CapabilityEngine, diagnostic UI |
| V0.3 | Sensor diagnostics | Not started — subscribe to the gyroscope, measure real sampling rate / jitter / stationary noise & bias / timestamp monotonicity, over a real collection window |
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
now — there is no code path that produces that result — because doing so
would require measured sensor jitter/noise (V0.3), measured camera stream
stability (V0.4), and measured synchronization quality (V0.7), none of
which exist yet. Extending the engine to reach higher levels means adding
real measurements first, not loosening the existing thresholds.

## What schema/architecture changes are expected next (V0.3)

- `DeviceProfile.SCHEMA_VERSION` will bump to 2 when a `sensorQuality`
  section (measured rate, jitter, stddev noise, bias, timestamp gaps) is
  added.
- `CapabilityThresholds.MAX_GYRO_TIMESTAMP_JITTER_MS_FOR_ADVANCED` and
  `MAX_GYRO_STATIONARY_STD_DEV_RAD_S` move from "documented but unused" to
  "applied," with real device data backing the chosen values instead of the
  provisional estimates in place today.
- A `sensors/SensorQualityTest.kt` component will own the actual
  SensorEventListener subscription, stationary-noise measurement window,
  and jitter computation described in spec section 5.
