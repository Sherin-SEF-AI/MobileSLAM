# ADR 0002 — `elapsedRealtimeNanos` is the single sync timebase

- Status: Accepted
- Date: 2026-06-19

## Context

Camera (Camera2/ARCore), IMU (`SensorEvent.timestamp`), and GNSS each report
timestamps that may originate from different clocks. Fusing VIO with IMU and
georeferencing against GNSS requires all streams on one monotonic clock.
`System.currentTimeMillis()` is wall-clock and subject to NTP corrections and
jumps — fatal for sub-millisecond sync.

## Decision

`SystemClock.elapsedRealtimeNanos()` is the one synchronization clock, exposed
via `core:common` `TimeSource`. Every MCAP message carries `timestamp_ns` in
this base. Per-stream timebase is detected (e.g.
`SENSOR_INFO_TIMESTAMP_SOURCE`); when a stream reports a different base, a
measured per-stream offset normalizes it. `System.currentTimeMillis()` is
permitted only for human-readable wall timestamps in metadata, isolated as
`TimeSource.wallClockMillis()`.

## Consequences

- One offset table per device/session; the SyncEngine (Phase 1) owns detection,
  drift, latency, and validation.
- Forbidden anti-pattern enforced by code review and the named API split.
