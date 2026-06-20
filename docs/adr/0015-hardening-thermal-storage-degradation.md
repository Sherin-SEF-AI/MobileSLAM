# ADR 0015 — Hardening: thermal/storage degradation never touches recording or sync

- Status: Accepted
- Date: 2026-06-20

## Context

Phase 9 hardens for 8h+ capture, thermal throttling, 100 GB datasets, battery,
and crash-recovery soak. The §10 invariant: under pressure, degrade perception
and non-essential render — **never recording or sync**.

## Decision

- **Pure policies** in `:core:common`:
  - `ThermalPolicy.plan(state, configuredHz)` → `DegradationPlan` whose only
    fields are `perceptionEnabled`, `perceptionHzCap`, `renderEnabled`. There is
    **no field that can disable recording or sync**, so the invariant is enforced
    structurally (a unit test asserts the field surface). Monotonic: hotter never
    relaxes a restriction.
  - `StoragePolicy.action(freeBytes)` → NORMAL / WARN / STOP_NEW_PERCEPTION /
    STOP_RECORDING. Recording is stopped only when the disk is critically full
    (< 500 MB) — continuing would corrupt the active MCAP — and that stop is the
    RecordingController's decision, not the degradation layer's.
- **Runtime managers** (`:app`): `ThermalManager` (PowerManager thermal listener),
  `StorageManager` (StatFs free + lazy trip-byte accounting, 100 GB-safe — never
  loads file contents), `BatteryMonitor` (drain %/h), and `DegradationController`
  that applies plans to `PerceptionController` (cadence cap / pause) and exposes a
  render flag. All start with a recording session and stop with it.
- **100 GB UIs**: paged asset access (`assetsPage(offset, limit)`, 200/page,
  scroll-to-load); Sessions already row-per-trip. No full-table loads.
- **Crash-recovery soak**: launch-time recovery (ADR 0008) already finalizes
  unsealed segments; the field protocol exercises repeated kills.

## Consequences

- The degradation logic is unit-tested (monotonic thermal plan, storage
  escalation, field-surface assertion). Real thermal throttle, 8h soak, battery,
  and 100 GB are device-validated per `docs/field-protocol.md`.
- Recording/sync cannot be degraded by any code path short of a critically full
  disk — the safety property is a type-level guarantee, not a convention.
