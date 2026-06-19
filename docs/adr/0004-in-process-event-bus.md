# ADR 0004 — In-process typed event bus over SharedFlow

- Status: Accepted
- Date: 2026-06-19

## Context

Recording, perception, fusion, and UI all need hot-path signals (frames, IMU
batches, GNSS fixes, poses, detections, sync/thermal warnings). Direct
cross-module calls on the hot path couple producers to consumers and risk a slow
consumer back-pressuring a sensor callback.

## Decision

A single typed `EventBus` in `:core:common` wraps a `MutableSharedFlow`
(`replay = 0`, buffered, `onBufferOverflow = DROP_OLDEST`). The event type is the
sealed `MapPilotEvent` in `:core:model`. Producers emit non-suspending via
`tryEmit`; `emit` returns `false` when an event is dropped so producers can
account for loss rather than block.

## Consequences

- A slow subscriber can never stall a sensor callback — at worst, events drop and
  are counted.
- Producers and consumers are decoupled; new subscribers add no producer cost.
- Guaranteed-delivery streams (the raw record path) do **not** use the bus — they
  go ring-buffer → MCAP writer directly. The bus carries live/observability
  signals, not the system-of-record stream.
