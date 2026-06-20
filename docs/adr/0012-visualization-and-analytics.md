# ADR 0012 — Visualization (MapLibre + GLES3) and analytics dashboard

- Status: Accepted
- Date: 2026-06-20

## Context

Phase 6 needs a 2D geo map (trajectory + assets + heatmaps), a 3D sparse-cloud +
keyframe-frustum view, and a quality dashboard — all computed from real session
data, no placeholder numbers (§9, §12).

## Decision

- **2D map = MapLibre Native** (`:viz:map`). A matte-dark style with no external
  tiles (fully offline) plus programmatic layers: a LineLayer for the trajectory,
  a CircleLayer for assets, and a HeatmapLayer for asset density. Layer GeoJSON is
  built by a pure, unit-tested `MapGeoJson`.
- **3D = GLES 3 renderer** (`:viz:render3d`), the locked OpenGL ES 3 fallback to
  Filament. A `GLSurfaceView` renders the sparse cloud (GL_POINTS, coloured by
  confidence) and keyframe frustums (GL_LINES) with orbit/pinch-zoom/pan. The
  vertex assembly (`PointCloudScene`: centroid centring + frustum line geometry)
  is pure and unit-tested; only the GL upload/draw is device-side. Filament
  remains a higher-fidelity seam.
- **Analytics = pure `QualityAnalyzer`** (`:analytics`): bounded, monotonic,
  documented sub-scores (SLAM continuity + feature density, GNSS fix/signal/sats/
  accuracy, trajectory sync-health + georef residual) plus convex-hull coverage
  area (Andrew's monotone chain + shoelace over a local equirectangular
  projection) and a composite reconstruction-readiness. Unmeasured inputs are
  excluded from their sub-score, never guessed.
- **Persistence for viz**: on stop, the trajectory is written as
  `trajectory.geojson` beside the MCAP and georeferenced landmarks are persisted
  to the DB, so Session Detail renders real recorded data (DB assets/landmarks +
  trajectory sidecar). Session Detail tabs (Map/3D/Assets/Quality/Export),
  Sessions list, and Map Explorer are wired to the repository.

## Consequences

- GeoJSON building, scene geometry, coverage area, and all quality scoring are
  verified off-device (16 new unit tests; 102 total). MapLibre + GLES rendering
  are device-validated.
- MapLibre adds ~30 MB of native libraries (APK ~133 MB debug); acceptable for a
  professional field tool, reducible via ABI splits for release.
- Quality scoring at the session level reuses stored capture-time scores as
  inputs where per-epoch raw metrics aren't persisted; live capture has the full
  raw inputs.
