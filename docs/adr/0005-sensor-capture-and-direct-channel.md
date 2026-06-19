# ADR 0005 — Sensor capture threading and SensorDirectChannel as opt-in

- Status: Accepted
- Date: 2026-06-19

## Context

IMU must run ≥100 Hz across six streams without sensor callbacks ever touching
disk/DB or the main thread. Some devices expose `SensorDirectChannel` for much
higher rates via shared memory, but its shared-memory record parsing is
device-dependent and cannot be validated in this environment without hardware.

## Decision

- All sensor sources run on dedicated `HandlerThread`s. Callbacks normalize the
  timestamp via the SyncEngine, push into a lock-free SPSC `RingBuffer`
  (`:core:common`), and batch onto the event bus. The writer thread (Phase 2)
  drains the ring buffer.
- The `SensorManager.registerListener` path is the **production default** — it
  reaches ≥100 Hz with an explicit sampling period and is correct on every
  device.
- `SensorDirectChannel` is implemented (capability probe + `MemoryFile` channel
  + reader) but **opt-in and capability-gated**: it reports UNAVAILABLE and
  stays off where unsupported, so we never silently run an unvalidated fast
  path. Its 104-byte record decoder is pure and unit-tested against synthetic
  buffers.

## Consequences

- Correctness does not depend on hardware we can't test here.
- The fast path can be enabled and validated per device later without touching
  the default flow.
- Ring buffer overflow counts as dropped samples in SyncEngine accounting, never
  blocks the producer.
