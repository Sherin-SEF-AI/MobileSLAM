# ADR 0013 — Export: device-native writers + cloud-format job dispatch

- Status: Accepted
- Date: 2026-06-20

## Context

Phase 7 needs device-native exports (MCAP, GeoJSON, PLY, PCD, CSV) that open in
standard tools, plus cloud-only formats (OBJ/GLTF, OpenDRIVE, Lanelet2, MBTiles,
Parquet) that must be **dispatched as jobs, never fabricated on-device** (§1, §7).

## Decision

- **Pure writers** in `:export`:
  - **PLY** (ASCII + binary little-endian): `x,y,z` floats + a `confidence`
    scalar field that CloudCompare/MeshLab display.
  - **PCD** (v0.7, ASCII + binary): `FIELDS x y z intensity`.
  - **GeoJSON**: one FeatureCollection with the trajectory LineString + asset
    Points (class/confidence/depth properties).
  - **CSV**: trajectory, assets, and a per-stream sensor summary.
  - **MCAP** is already produced during recording — export references the
    existing file, it is not rewritten.
- Point clouds export in the **metric VIO frame** (not lon/lat) so they load as a
  real metric cloud in CloudCompare/PCL.
- **Cloud formats** are modelled as a typed `ExportFormat` (with a
  `deviceNative` flag). `ExportService` writes device-native formats inline and,
  for cloud formats, returns a `CloudJobDescriptor` (state `QUEUED`) — intent and
  state only, **no fabricated geometry**. Actual upload + processing is Phase 8.

## Consequences

- The writers are pure and unit-tested (round-trip parse), and the golden files
  were validated by reference tooling: the **`plyfile`** library reads both ASCII
  and binary PLY (500 verts, correct properties + values); PCD v0.7 and GeoJSON
  validated by reference parsers. This is the off-device equivalent of "opens in
  CloudCompare / QGIS".
- Cloud-only formats surface a clear "requires processing" state and dispatch a
  job — the no-fabrication rule is enforced by construction (there is no
  on-device OBJ/OpenDRIVE writer to misuse).
