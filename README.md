# EIS Camera — Universal Android Gyro-Based EIS Camera

**Status: V0.1 (project skeleton) + V0.2 (Device Capability Scanner).**
Nothing about real-time stabilization exists yet — see "Why this scope"
below and `docs/ROADMAP.md` for what comes next.

## Why this scope, and not the full pipeline

The project brief's own development process (section 39) says: work
incrementally, don't skip to a "finished" version, and every stage must be
testable. Its own claims policy (section 42) says: never say a device
"supports Advanced EIS" unless the capability engine actually has evidence
for it. Taken together, those two rules mean the first deliverable has to
be the measurement foundation, not a stabilization pipeline running on
faked or assumed device quality data. So this drop is:

- A real, buildable Android/Kotlin project (Gradle + AGP + Compose).
- A **Device Capability Scanner** that inspects, per spec section 4:
  - **Sensors** — every gyroscope/accelerometer/rotation-vector/etc. the
    platform declares, with its declared (not measured) rate, resolution,
    range, reporting mode, vendor/name.
  - **Cameras** — every `Camera2` camera id independently: facing,
    hardware level, sensor size/pixel array, focal lengths, apertures, OIS
    availability, vendor digital-EIS availability, output formats/sizes,
    FPS ranges, exposure range, logical-multi-camera / physical camera ids.
  - **Processing** — CPU core count, ABIs, memory, low-RAM flag, a real
    GPU driver query (renderer/vendor/GL version via a throwaway EGL
    pbuffer context), and the declared hardware video encoder list.
- A **DeviceProfile** (spec section 8) — a versioned, JSON-persisted
  aggregate of the above, invalidated automatically when `Build.FINGERPRINT`
  changes (OS/OEM update) or the schema version changes.
- A **CapabilityEngine** (spec section 9) that is deliberately conservative:
  at this stage it can only return `UNSUPPORTED` (when a hard requirement —
  gyroscope, camera, or a CPU-core floor — is provably absent) or
  `LEVEL_1_BASIC` **marked provisional**, because no sensor-quality,
  camera-quality, or gyro/camera synchronization measurement exists yet.
  Every threshold it uses lives in `CapabilityThresholds.kt`, documented per
  spec section 33 (meaning, units, origin, effect of raising/lowering,
  device-dependence, whether it's experimentally tuned).
- A **diagnostic screen** (Compose) that shows the scan results and the
  engine's reasoning, so nothing is hidden behind a single pass/fail badge.

## Architecture

```
app/src/main/java/com/eiscamera/
├── app/            MainActivity (entry point)
├── logging/        EisLog — structured per-subsystem logging (spec §32)
├── sensors/        SensorInfo, SensorInventory (static SensorManager scan)
├── camera/         CameraInfo, CameraInventory (static Camera2 scan)
├── processing/      ProcessingInfo, ProcessingInventory, GpuInfoProbe (EGL)
├── deviceprofile/  DeviceProfile, DeviceIdentity, DeviceProfileRepository
├── capability/     CapabilityLevel, CapabilityThresholds, CapabilityEngine
├── diagnostics/    DeviceScanCoordinator (orchestrates the full scan)
└── ui/             ScanViewModel, DiagnosticScreen, theme/
```

This intentionally does not yet include `synchronization/`, `motion/`,
`stabilization/`, `lens/`, `rendering/`, `encoding/`, `calibration/`, or
`performance/` packages from the target structure in spec section 27 —
they don't have anything real to contain until later roadmap stages
produce actual measurements and algorithms for them. Adding empty
placeholder packages now would be exactly the kind of "unnecessary
abstraction layer" section 27 warns against.

## Terminology used throughout the code (spec section 41)

- **AVAILABLE** — the hardware/API exposes the feature (e.g. a Sensor
  object exists).
- **MEASURED** — the app actually measured the behavior on this device
  (e.g. CPU core count via `Runtime.availableProcessors()`, or the GPU
  driver strings via a real EGL query).
- **ESTIMATED** — the app inferred a value it could not directly read
  (e.g. hardware-encoder detection on API < 29, which falls back to a
  codec-name heuristic).
- **PROVISIONAL** — a classification made without the full evidence a
  higher-confidence answer would need.

The `CapabilityEngine`'s `reasons` list prefixes every line with one of
these words so nothing is stated more confidently than it was determined.

## Building — GitHub Actions (no Android Studio required)

This repo builds itself. `.github/workflows/android-build.yml` runs on
every push to `main` (and on demand): it installs JDK 17 + the Android SDK
on a GitHub-hosted runner, runs the unit tests, assembles a debug APK, and
uploads both as workflow artifacts. You never need to install Android
Studio to get a working APK.

**First-time setup:**

1. Create an empty repository on GitHub (no README/gitignore/license —
   this project already has those, and adding them on GitHub's side causes
   a merge conflict on first push). Either via the web UI, or:
   ```bash
   gh repo create eis-camera --private --source=. --remote=origin
   ```
2. From inside this project folder:
   ```bash
   git remote add origin https://github.com/<you>/eis-camera.git
   git branch -M main
   git push -u origin main
   ```
   (skip `git remote add` if you used `gh repo create --source=.`, it does
   that for you).
3. Open the **Actions** tab on your repo — the workflow starts
   automatically on that push. It takes a few minutes.
4. When it finishes, open the run and scroll to **Artifacts**:
   - `eis-camera-debug-apk` — the installable APK. Download the zip,
     unzip it, and sideload the `.apk` onto your OPPO F31 5G (or any test
     device) via `adb install` or by transferring the file directly.
   - `unit-test-reports` — HTML test results, useful if the `test` step
     fails.
5. Every subsequent push re-runs the build automatically. You can also
   trigger it manually from the Actions tab (`Run workflow` — this repo's
   workflow has `workflow_dispatch` enabled) without pushing anything.

This project deliberately does **not** commit a Gradle wrapper jar
(`gradle-wrapper.jar` is a binary file). The CI workflow installs Gradle
8.9 directly via `gradle/actions/setup-gradle` and calls `gradle` rather
than `./gradlew` — nothing extra to generate. If you ever want a *local*
`./gradlew` too (e.g. to build without pushing to GitHub each time),
install Gradle locally and run `gradle wrapper --gradle-version 8.9` once
inside the project folder.

**Note on compileSdk/build-tools versions:** the workflow explicitly
installs `platforms;android-35` and `build-tools;35.0.0` to match this
project's `compileSdk = 35`. If you bump `compileSdk`/`targetSdk` in
`app/build.gradle.kts` later, update those two `sdkmanager` package names
in the workflow to match, or the build will fail with a "missing platform"
error.

## Building — Android Studio (optional, local alternative)

If you'd rather work locally: open the project root in a recent Android
Studio (targets AGP 8.6 / Kotlin 2.0 / Compose BOM 2024.09), let it
generate the Gradle wrapper when prompted, accept any suggested
AGP/Kotlin/Compose upgrades, and run on a physical device — the emulator's
virtual gyroscope/camera won't tell you anything real about EIS
feasibility. Unit tests (`CapabilityEngineTest`,
`DeviceProfileSerializationTest`) run via Android Studio's test runner.

No `CAMERA` runtime permission prompt appears yet, either way — this build
only reads static `CameraCharacteristics`, which Android does not gate
behind that permission. It's added starting at V0.6 (live preview).

## No OEM-specific behavior

Per spec sections 23/24, nothing here special-cases the OPPO F31 5G or any
other manufacturer/model. Every value shown comes from `SensorManager`,
`CameraManager`/`CameraCharacteristics`, `Runtime`, `ActivityManager`,
`MediaCodecList`, or a live EGL query — not from a hard-coded device table.
Spec section 22 does allow an *optional*, clearly-labeled validated-device
database later; none exists yet, and none is needed for a static scan.

## Known placeholders / rough edges in this drop

- The adaptive launcher icon is a plain placeholder vector (a ring +
  crosshair), not real branding — swap `ic_launcher_foreground.xml` /
  `ic_launcher_background.xml` whenever real branding exists.
- `minFrameDurationsNs` on `CameraInfo` is always empty by design (see the
  kdoc on that field) — it needs a concrete resolution/format choice to be
  meaningful, which doesn't happen until a recording configuration exists.
- Pre-Android-13 (API < 29) hardware-encoder detection is a name-based
  heuristic, explicitly marked `ESTIMATED` rather than `MEASURED` in code
  comments and in `CodecInfoSummary` — see its kdoc.
- No calibration workflow, no diagnostic data export (CSV/JSON dump for
  offline analysis), no debug overlay with live FPS/latency numbers yet —
  all of those need a running camera/gyro pipeline first (V0.5+).

See `docs/ROADMAP.md` for what's next and why the `CapabilityEngine` is
structurally prevented from over-claiming today.
