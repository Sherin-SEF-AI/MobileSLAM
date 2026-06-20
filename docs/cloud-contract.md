# MapPilot Cloud Contract (v1)

The device is the edge; the cloud is heavy compute. This document is the REST +
job contract the backend MUST implement and the device codes against. The device
obligations are only: **upload artifacts, create/poll jobs, fetch results with
provenance.** The cloud never returns geometry the device presents as real unless
a real pipeline produced it — every artifact carries a `provenance` field.

Base URL: `{BASE}/v1`. All bodies JSON unless noted. Auth: `Authorization: Bearer
<token>` (omitted by the dev reference server).

## Provenance

Every cloud-derived artifact carries one of:
`ON_DEVICE | CLOUD_REFINED | CLOUD_RECONSTRUCTION | MANUAL`. The app visually
distinguishes non-`ON_DEVICE` artifacts.

## 1. Resumable chunked upload

Artifacts (mp4, MCAP, exports) are uploaded in fixed-size chunks so an
interrupted transfer resumes without re-sending received chunks.

### Create upload session
`POST /uploads`
```json
{ "tripId": 1718900000000, "artifact": "trip.mcap", "totalBytes": 104857600,
  "chunkSize": 8388608, "sha256": "<hex of whole file>" }
```
→ `200`
```json
{ "uploadId": "u_abc123", "chunkSize": 8388608, "receivedChunks": [] }
```

### Query status (drives resume)
`GET /uploads/{uploadId}` → `200`
```json
{ "uploadId": "u_abc123", "receivedChunks": [0,1,2], "totalChunks": 13,
  "state": "UPLOADING" }
```

### Upload one chunk
`PUT /uploads/{uploadId}/chunks/{index}`
Headers: `Content-Type: application/octet-stream`, `X-Chunk-SHA256: <hex>`.
Body: raw chunk bytes.
→ `200 {"index": 3, "received": true}`
→ `409` if the chunk checksum does not match (client re-sends).

### Complete
`POST /uploads/{uploadId}/complete` → verifies all chunks + whole-file sha256.
→ `200 {"artifactId": "a_xyz", "state": "COMPLETE"}`
→ `409 {"missingChunks": [7]}` if incomplete.

## 2. Processing jobs

### Create job
`POST /jobs`
```json
{ "artifactId": "a_xyz", "type": "SFM_REFINE", "tripId": 1718900000000 }
```
`type ∈ { SFM_REFINE, MVS_DENSE, GAUSSIAN_SPLAT, OPEN_VOCAB_DETECT,
MAP_GEN_OPENDRIVE, MAP_GEN_LANELET2, VECTOR_TILES }`.
→ `200 {"jobId": "j_123", "state": "QUEUED"}`

### Poll job
`GET /jobs/{jobId}` → `200`
```json
{ "jobId": "j_123", "state": "PROCESSING", "progress": 0.42,
  "provenance": "CLOUD_REFINED", "resultUrl": null, "error": null }
```
`state ∈ { QUEUED, PROCESSING, READY, FAILED }`.

### Fetch result
`GET /jobs/{jobId}/result` → `200` artifact bytes (only when `state == READY`),
with header `X-Provenance: CLOUD_REFINED`. → `409` if not ready.

## 3. Device state mapping

| Server upload/job state | App `UploadJob.state` |
|---|---|
| session created | `QUEUED` |
| chunks transferring | `UPLOADING` |
| upload complete, job QUEUED/PROCESSING | `PROCESSING` |
| job READY | `READY` |
| any FAILED / error | `FAILED` |

## 4. Integrity & resume

- Per-chunk `sha256` verified server-side (`409` on mismatch → client retries
  that chunk only).
- Whole-file `sha256` verified at `complete`.
- The client resumes by calling `GET /uploads/{id}` and skipping
  `receivedChunks`. Safe to retry any chunk (idempotent by index).

## 5. Dev reference server

`/server-dev` implements this contract for integration testing. It returns real
lifecycle states and verifies checksums, but **runs no real pipeline** — its job
results are markers/echoes with explicit provenance, never fabricated geometry or
detections presented as genuine output.
