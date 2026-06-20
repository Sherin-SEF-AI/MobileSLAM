# ADR 0014 — Cloud client: resumable upload + job contract

- Status: Accepted
- Date: 2026-06-20

## Context

The device must upload real recordings and poll real job states, and **never
fabricate cloud results** (§1). Phase 8 needs resumable chunked upload with
integrity, a job API client, provenance handling, and a dev reference server.

## Decision

- **Contract first** (`docs/cloud-contract.md`): chunked resumable upload
  (create session → status → put chunk → complete), processing jobs (create →
  poll → fetch result), per-chunk + whole-file SHA-256, and a device state map
  `QUEUED/UPLOADING/PROCESSING/READY/FAILED`. Every cloud artifact carries
  `provenance`.
- **`:cloud:client`**:
  - Pure, tested `ChunkPlan` (fixed-size chunks, resume = skip received,
    progress) + `Integrity` (SHA-256).
  - `CloudTransport` interface + `HttpCloudTransport` (`HttpURLConnection`, no
    extra deps).
  - `ChunkedUploader`: resumes via server status, re-sends only missing chunks,
    retries a chunk on a 409 checksum mismatch; survives a mid-upload failure.
  - `CloudStateMapper` (pure) maps server states to app states.
  - `UploadWorker` (WorkManager, Hilt) runs upload + job dispatch off the hot
    path with exponential backoff; survives process death and resumes via the
    persisted server `uploadId`. `CloudUploadManager` enqueues it; the `UploadJob`
    row mirrors state.
- **Provenance in the UI**: the Jobs screen always shows `provenance` and tags
  non-`ON_DEVICE` artifacts as cloud-derived.
- **Dev reference server** (`/server-dev`): zero-dep stdlib implementation that
  verifies checksums and returns real lifecycle states but **runs no pipeline** —
  results are markers with explicit provenance, never fabricated geometry.

## Consequences

- The uploader's resume + integrity logic is unit-tested end-to-end against an
  in-memory contract implementation (mid-upload drop → resume sends only the
  remaining chunks; 409 → retry). The full contract was additionally validated
  over **real HTTP** against the dev server (create → resume-after-drop →
  409-retry → complete → job QUEUED→PROCESSING→READY → result with provenance).
- `com.sun.net.httpserver` is unavailable on the Android unit-test classpath, so
  the in-test server uses an in-memory transport; real HTTP is proven via the
  Python dev server instead.
- No path exists to present fabricated cloud geometry as real: the device only
  uploads, polls, and displays provenance-tagged results.
