# ADR 0011 — Local spatial + vector database (Room + R*Tree + exact-cosine)

- Status: Accepted
- Date: 2026-06-20

## Context

The device needs a queryable spatial + vector store (§6.3): radius/bbox spatial
queries over assets/fixes/landmarks and semantic similarity over asset
embeddings. The locked decision was Room over requery's bundled SQLite with the
R*Tree module and the `sqlite-vec` extension.

## Decision

- **Room is the system of record** for the lower-volume, query-serving entities
  (trips, keyframes, gnss fixes + epoch summaries, landmarks, assets, embeddings,
  events, upload jobs). Full-rate raw streams stay in MCAP — no 30 fps frames or
  100 Hz IMU rows.
- **R*Tree** virtual tables (`asset_rtree`, `gnss_fix_rtree`, `landmark_rtree`)
  are created in a Room `Callback` and kept in sync by triggers; spatial queries
  prefilter via the rtree, then refine by exact haversine. Spatial reads use
  `@RawQuery` to bypass Room's compile-time table validation.
- **SQLite provider**: `io.requery:sqlite-android` is **no longer published to
  Maven Central** (the locked dependency is unresolvable). We therefore use the
  **platform SQLite** via Room's default factory and **detect R*Tree
  availability** at DB creation: if the platform build lacks `SQLITE_ENABLE_RTREE`
  the callback records it and spatial queries fall back to an **indexed lat/lon
  bbox scan** — identical results, no acceleration. (Seam preserved: a bundled
  SQLite AAR can be slotted into the factory later without touching queries.)
- **Vector search**: an exact in-Kotlin **cosine** ranking over stored float32
  embeddings (`VectorMath`). Correct and adequate at on-device asset scale;
  `sqlite-vec` is the accelerated backend seam for large multi-session sets and
  does not change results. Returns empty when no embeddings exist — no fabricated
  matches. The text→vector embedder (CLIP-like) is a separate model seam.

## Consequences

- The actual production spatial SQL is verified against a real R*Tree SQLite
  (xerial sqlite-jdbc) in a pure-JVM test — same SQL strings the app runs. Vector
  cosine/top-K and haversine/bbox are unit-tested; the Room layer is exercised
  end-to-end on Robolectric.
- No fragile native dependency; spatial acceleration is best-effort with a
  correct fallback.
- Semantic search is functional today given embeddings; producing asset
  embeddings on-device (or via cloud) is downstream work.
