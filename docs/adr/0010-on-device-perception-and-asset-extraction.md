# ADR 0010 — On-device perception (YOLO11n/LiteRT) + road-asset extraction

- Status: Accepted
- Date: 2026-06-20

## Context

Phase 4 needs real on-device detection + depth feeding road-asset extraction:
detect → temporal dedup → backproject → georeference → DB/`/assets`, at a
reduced cadence that never back-pressures the 30 fps record path (§10).

## Decision

- **Detector**: real **YOLO11n** via **LiteRT** (`org.tensorflow.lite`), the
  float16 `.tflite` bundled in `:perception:detection` assets. The `[1,84,8400]`
  head is decoded + NMS'd by a pure, unit-tested `YoloDecoder`; `Letterbox`
  handles aspect-preserving input and the inverse box mapping.
- **Classes**: only COCO classes that are genuinely road assets are mapped
  (`traffic light`→TRAFFIC_LIGHT, `stop sign`→TRAFFIC_SIGN, hydrant/meter→POLE).
  Categories absent from COCO (potholes, lane/road markings, speed breakers,
  construction zones) require a purpose-trained model and come from the cloud
  open-vocabulary pipeline — **never fabricated on-device**. The full COCO label
  is retained per detection.
- **Reduced cadence**: a pure `FrameScheduler` throttles to `perceptionHz`
  (default 8) and drops frames while inference is in flight. Analysis frames come
  from a dedicated YUV `ImageReader` target on the camera (separate from preview
  + encoder), converted NV21→RGB by a pure, tested `Yuv` converter.
- **Backprojection** (`:assets:extraction`, pure + tested): pixel + metric depth
  + intrinsics + camera pose → world point (OpenCV pinhole + rigid transform);
  null on non-positive depth. `AssetTracker` dedups same-class detections within
  a merge radius via running-mean world positions. Georeferencing applies the
  Umeyama VIO→ENU transform then WGS84 ENU→geo.
- **Depth**: ARCore Depth API is the on-device metric primary; Depth Anything V2
  (LiteRT) is the monocular fallback. The Depth Anything model is **not bundled**
  here, so its loader returns `Unavailable` — and the extractor treats missing
  depth as "no geolocation" (DEGRADED) rather than inventing a distance.

## Consequences

- Decoder, NMS, letterbox, YUV, backprojection, dedup, and class mapping are all
  unit-tested off-device (23 tests). The bundled float16 model was verified to
  run and produce correct detections (bus 0.94, persons) via the reference
  toolchain.
- On-device acceptance (model latency on mid-tier Snapdragon, depth from ARCore,
  georeferenced assets on a real drive) requires hardware. Without depth +
  alignment, detections are still published but assets are not georeferenced —
  no fabricated geo.
- float16 (not int8) is shipped; int8 calibration data exists in `yolo_export`
  for a later size/latency optimization.
