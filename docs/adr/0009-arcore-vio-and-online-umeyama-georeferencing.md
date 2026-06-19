# ADR 0009 — ARCore VIO backend + online Umeyama VIO→ENU georeferencing

- Status: Accepted
- Date: 2026-06-19

## Context

Phase 3 needs on-device visual-inertial SLAM (poses, sparse cloud), a
georeferenced trajectory aligning the VIO frame to a local ENU frame from GNSS,
and all of it logged to MCAP. ARCore is the locked VIO backend (§2); global
bundle adjustment is cloud-only.

## Decision

- **Backend-agnostic `SlamEngine`** (`:slam:core`) with pure, tested
  `KeyframeSelector` (motion + min-interval) and `PoseGraph` (trajectory length
  over tracking poses, tracking-loss-aware). The ARCore backend
  (`:slam:arcore`) drives a `Session` on a dedicated thread with an offscreen
  EGL context + OES camera texture, emitting `PoseUpdate` / `KeyframeSelected` /
  `LandmarksUpdated` (`acquirePointCloud`) to the bus. Availability is checked;
  unsupported devices report `available=false` with a reason — never fabricated
  poses.
- **Online georeferencing** (`:slam:fusion`): an `Umeyama` similarity solver
  (scale + rotation + translation, via a from-scratch 3×3 Jacobi-eigen SVD) and
  WGS84 geodetic⇄ECEF⇄ENU. `GnssVioFusion` buffers (VIO-pose, GNSS-ENU)
  correspondences, re-solves as fixes arrive, and republishes each VIO pose as an
  `EnuPose` — but only once enough correspondences exist and the RMS residual is
  acceptable; otherwise it stays unaligned rather than emitting a bogus
  georeference. `:geo:trajectory` builds the georeferenced path and exports
  GeoJSON/CSV.
- Poses/landmarks/ENU flow over the bus, so the Phase-2 `RecordingSession`
  already persists them to `/pose`, `/landmarks`, `/pose/enu`.

## Camera arbitration (the remaining device-only step)

This backend runs ARCore with **its own camera**. Unifying it with the Camera2
record path via ARCore `SharedCamera` (so VIO and mp4/metadata share one camera)
is an on-hardware integration: it cannot be exercised without an ARCore device.
SLAM start is therefore **best-effort** — if the camera is busy, the engine
reports UNAVAILABLE loudly and recording continues uninterrupted (recording is
never gated by SLAM). The pose/landmark/ENU data contracts and the fusion math
are final and fully unit-tested; only the camera-sharing glue is device-pending.

## Consequences

- Umeyama, ENU, keyframe, pose-graph, and trajectory logic are verified
  off-device (known-transform recovery, ENU round-trips, haversine cross-check).
- On-device acceptance (real loop → live trajectory, GNSS alignment, MCAP poses)
  requires an ARCore phone and the SharedCamera glue.
