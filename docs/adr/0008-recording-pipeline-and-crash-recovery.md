# ADR 0008 — Recording pipeline threading and crash recovery by rewrite

- Status: Accepted
- Date: 2026-06-19

## Context

Recording must be lossless for high-rate IMU, must not be blocked by perception
or upload (§10), and must survive a crash with a valid, recoverable file. The
MCAP writer is single-threaded.

## Decision

- **One writer thread** owns the `McapTripWriter`. High-rate IMU is drained
  losslessly from lock-free ring buffers on that thread; camera/GNSS/pose/asset/
  event records arrive via the bus and are *posted* to the same thread. The
  lossy `ImuBatch` bus event is ignored by the recorder (HUD-only) to avoid
  double-writing.
- **Chunks are sealed + flushed on an interval** (`mcapChunkSealIntervalMs`), and
  the file **rolls to a new segment** past `mcapSegmentRolloverBytes` for long
  sessions. Each sealed chunk is durable on disk.
- **Crash recovery by rewrite**: on launch, every `.mcap` lacking a footer is
  read with the tolerant reader (which stops cleanly at the truncated tail) and
  its complete, CRC-valid chunks are rewritten through the same tested
  `McapWriter` to produce a fully-indexed file. The broken original is kept as
  `*.broken` for forensics. This reuses tested code rather than surgically
  patching a corrupt file, and guarantees validity up to the last sealed chunk.
- **Video is decoupled**: the mp4 encoder runs on its own drain thread; if it
  fails to start, MCAP recording continues (recording is never gated by video).

## Consequences

- The single-writer invariant keeps the writer lock-free and correct.
- Unsealed (in-flight) chunk data is the only possible loss on crash — bounded by
  the seal interval — which matches "valid up to the last sealed chunk".
- Recovery is unit-tested: a truncated multi-chunk file is rewritten to a valid,
  indexed, CRC-clean MCAP with zero message loss from sealed chunks.
