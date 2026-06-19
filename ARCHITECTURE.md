# MapPilot — Architecture

Living system map. Updated each phase. This describes **what exists now**, not
aspirations; sections marked _(planned: Phase N)_ are seams, not implementations.

## What MapPilot is

A production Android mobile-mapping and Visual-Inertial SLAM platform. The phone
(Camera + IMU + GNSS) is a spatial capture instrument that produces georeferenced
trajectories, sparse maps/point clouds, road-asset databases, and synchronized
MCAP recordings. **The app is the edge**; heavy compute (global BA, dense MVS,
NeRF/3DGS, map-format generation) is a separate cloud backend the app holds a
real client contract to — it never fabricates cloud results.

## Prime constraints (enforced in code)

- **One clock:** `SystemClock.elapsedRealtimeNanos()` is the sync timebase
  (`core:common` `TimeSource`). `System.currentTimeMillis()` is display-only.
- **No invented data:** unavailable capabilities surface
  `MapPilotResult.Unavailable` / `Degraded` — never a silent fake.
- **Recording is sovereign:** owned by a foreground service; never gated by
  perception or upload.
- **Hot path never touches disk/DB:** sensor callbacks → ring buffer → writer
  thread. Events flow over an in-process `SharedFlow` bus, not direct calls.

## Module graph

```
:app  (Compose UI, navigation, DI host, RecordingService)
  └─ :core:common ─ :core:model
```
_(Phase 0: feature modules below are wired and compiling but empty.)_

| Module | Responsibility | Status |
|---|---|---|
| `:core:model` | Domain models (geometry, sensors, SLAM, assets, trip) + `MapPilotEvent` | **built** |
| `:core:common` | `MapPilotResult`, `TimeSource`, dispatchers, logging, `CaptureConfig`, `EventBus`, DI | **built** |
| `:core:database` | Room + SQLite(R*Tree) + sqlite-vec | planned: Phase 5 |
| `:core:time-sync` | `SyncEngine`: timebase normalization, drift/latency/validation | **built** |
| `:sensors:camera` | Camera2 capture, intrinsics/exposure/ISO, ts-source detection | **built** (ARCore SharedCamera: Phase 3) |
| `:sensors:imu` | 6 IMU streams ≥100 Hz, ring-buffered, SensorDirectChannel (opt-in) | **built** |
| `:sensors:gnss` | Location + raw GNSS + satellite status (6 constellations) | **built** |
| `:recording:mcap` | Custom chunked/indexed/CRC/segmented MCAP writer + self-describing protobuf + crash recovery | **built** |
| `:recording:video` | MediaCodec mp4 encode + frame↔PTS↔ts map | **built** (device-validated) |
| `:slam:core` | `SlamEngine` interface, `PoseGraph`, `KeyframeSelector` | **built** |
| `:slam:arcore` | ARCore VIO backend (Session + offscreen EGL, poses/landmarks) | **built** (SharedCamera glue device-pending) |
| `:slam:fusion` | Umeyama VIO→ENU + WGS84 geodesy + online `GnssVioFusion` | **built** |
| `:perception:core` | `InferenceEngine`, frame scheduler | planned: Phase 4 |
| `:perception:detection` | YOLO11 (LiteRT/ONNX/NCNN) | planned: Phase 4 |
| `:perception:depth` | Depth Anything V2 + ARCore depth | planned: Phase 4 |
| `:assets:extraction` | detect→dedup→backproject→DB | planned: Phase 4 |
| `:geo:trajectory` | `TrajectoryBuilder` + GeoJSON/CSV export | **built** |
| `:geo:mapping` | sparse map / asset map assembly | planned: Phase 6 |
| `:search` | spatial + vector + semantic queries | planned: Phase 5 |
| `:export` | MCAP/GeoJSON/PLY/PCD/CSV + cloud adapters | planned: Phase 7 |
| `:cloud:client` | resumable upload, job API, provenance | planned: Phase 8 |
| `:viz:map` / `:viz:render3d` | MapLibre 2D / Filament 3D | planned: Phase 6 |
| `:analytics` | quality scoring | planned: Phase 6 |

## Layering

Clean Architecture + MVVM. Domain in `:core:model`; repositories behind
interfaces; ViewModels expose immutable `StateFlow`. Cross-module hot-path
communication is exclusively via the `EventBus` (`MapPilotEvent` over a
`SharedFlow`, `DROP_OLDEST` so a slow subscriber can't back-pressure a sensor).

## Threading model

- Sensor/IMU callbacks → lock-free ring buffer → MCAP writer thread.
  Callbacks never touch disk or DB. _(Phase 1–2)_
- ARCore on its frame thread; pose log decoupled. _(Phase 3)_
- Perception on its own dispatcher at reduced cadence (5–10 Hz), decoupled from
  30 fps recording. _(Phase 4)_
- DB writes batched on `Dispatchers.IO`. _(Phase 5)_

## Build

- Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, JDK 17.
- compileSdk 35 / minSdk 31 (Android 12 — required for typed FGS, SharedCamera, raw GNSS).
- Convention plugins in `build-logic/` keep modules DRY
  (`mappilot.android.library`, `.compose`, `mappilot.kotlin.library`,
  `mappilot.android.hilt`).
- Version catalog: `gradle/libs.versions.toml`.

## Toolchain note (this dev box)

No physical ARCore device is attached during development; device-dependent
acceptance (live VIO, raw GNSS, NNAPI delegates) is validated on hardware over
adb. CI and local gates verify compilation, unit-testable logic, and lint.
```
```
