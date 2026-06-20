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
| `:core:database` | Room + R*Tree spatial (platform SQLite + fallback) + exact-cosine vectors | **built** |
| `:core:time-sync` | `SyncEngine`: timebase normalization, drift/latency/validation | **built** |
| `:sensors:camera` | Camera2 capture, intrinsics/exposure/ISO, ts-source detection | **built** (ARCore SharedCamera: Phase 3) |
| `:sensors:imu` | 6 IMU streams ≥100 Hz, ring-buffered, SensorDirectChannel (opt-in) | **built** |
| `:sensors:gnss` | Location + raw GNSS + satellite status (6 constellations) | **built** |
| `:recording:mcap` | Custom chunked/indexed/CRC/segmented MCAP writer + self-describing protobuf + crash recovery | **built** |
| `:recording:video` | MediaCodec mp4 encode + frame↔PTS↔ts map | **built** (device-validated) |
| `:slam:core` | `SlamEngine` interface, `PoseGraph`, `KeyframeSelector` | **built** |
| `:slam:arcore` | ARCore VIO backend (Session + offscreen EGL, poses/landmarks) | **built** (SharedCamera glue device-pending) |
| `:slam:fusion` | Umeyama VIO→ENU + WGS84 geodesy + online `GnssVioFusion` | **built** |
| `:perception:core` | Detector/DepthEstimator interfaces, `FrameScheduler`, `Yuv` | **built** |
| `:perception:detection` | YOLO11n (LiteRT) + pure `YoloDecoder`/`Letterbox` + COCO map | **built** (model bundled, verified real) |
| `:perception:depth` | Depth Anything V2 (LiteRT) fallback; ARCore depth primary | **built** (model not bundled → loud Unavailable) |
| `:assets:extraction` | `Backprojection` + `AssetTracker` dedup (pure, tested) | **built** |
| `:geo:trajectory` | `TrajectoryBuilder` + GeoJSON/CSV export | **built** |
| `:geo:mapping` | sparse map / asset map assembly | planned: Phase 6 |
| `:search` | `SearchService`: radius/bbox spatial, class filter, semantic (cosine) | **built** |
| `:export` | PLY/PCD/GeoJSON/CSV writers + cloud-format job dispatch | **built** |
| `:cloud:client` | resumable chunked upload + job API + provenance + WorkManager | **built** |
| `:viz:map` | MapLibre 2D: trajectory + assets + heatmap (offline style) | **built** |
| `:viz:render3d` | GLES3 sparse-cloud + keyframe-frustum (orbit/zoom/pan) | **built** |
| `:analytics` | `QualityAnalyzer` (SLAM/GNSS/trajectory/coverage/reconstruction) | **built** |

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
