# ADR 0007 — Custom Kotlin MCAP writer with self-describing protobuf schemas

- Status: Accepted
- Date: 2026-06-19

## Context

The system of record for full-rate raw streams is MCAP (§2). We need chunked,
indexed, CRC-checked, crash-survivable output with protobuf messages that any
MCAP consumer (Foxglove, the `mcap` tooling) can decode without an external
schema. There is no maintained first-party Kotlin/Android MCAP writer.

## Decision

Implement a custom Kotlin MCAP writer (`:recording:mcap`) per the mcap.dev spec:
Magic, Header, data-section Schema/Channel copies, repeated (Chunk +
per-channel MessageIndex) groups sealed on size/interval, DataEnd with
data-section CRC, summary section (Schema, Channel, ChunkIndex, Statistics),
SummaryOffsets, Footer, Magic.

- **Message encoding = protobuf**, schemas embedded as a serialized
  `google.protobuf.FileDescriptorSet` (deps-first) so files are self-describing.
- Use **full `protobuf-java`** (not lite) so generated messages expose runtime
  descriptors, letting us build the FileDescriptorSet at runtime with no
  descriptor-file resource plumbing.
- Per-chunk CRC32 is always computed (used by recovery); file-level
  `data_section_crc` is computed; `summary_crc` is left 0 (spec-permitted
  "not available").

## Consequences

- Output is validated by the reference `mcap` Python reader: valid summary,
  chunk index per chunk, all topics, and dynamic protobuf decode from the
  embedded schemas — including NavIC/IRNSS satellites.
- `protobuf-java` adds ~1.5 MB vs lite; acceptable for a professional capture app
  and worth the runtime-descriptor simplicity.
- The writer is pure JVM, so the whole pipeline is golden-tested off-device.
