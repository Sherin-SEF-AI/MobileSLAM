# ADR 0006 — Phase-1 camera uses Camera2 metadata path; ARCore SharedCamera in Phase 3

- Status: Accepted
- Date: 2026-06-19

## Context

The locked camera+SLAM path is ARCore `SharedCamera` (§2). But Phase 1 needs
only real per-frame metadata and timestamp-source detection for the sync HUD —
not VIO. Standing up an ARCore session, GL context, and SharedCamera purely for
metadata would be premature.

## Decision

Phase 1 uses a Camera2 capture session that targets a preview `Surface` (or an
internal `SurfaceTexture` when headless) and extracts real metadata from each
`TotalCaptureResult`: `SENSOR_TIMESTAMP`, exposure, ISO, and calibrated
intrinsics (`LENS_INTRINSIC_CALIBRATION` + `LENS_DISTORTION`, scaled from the
pre-correction active array to output resolution). `SENSOR_INFO_TIMESTAMP_SOURCE`
drives the SyncEngine's per-stream offset (REALTIME → 0, UNKNOWN → measured).

ARCore `SharedCamera` is wired in Phase 3 as the SLAM-coupled path; CameraX
`VideoCapture` is the record-only path (Phase 2). The Camera2 controller here is
the shared foundation both wrap.

## Consequences

- Phase 1 ships a real, testable camera path with correct timestamps and
  intrinsics, no ARCore dependency yet.
- Intrinsics are returned only when the device reports calibration; otherwise the
  HUD shows "intrinsics UNAVAILABLE" rather than a fabricated pinhole model.
- The intrinsics scaling math is pure and unit-tested.
