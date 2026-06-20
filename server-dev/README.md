# MapPilot dev reference server

A **zero-dependency** stdlib implementation of the cloud contract
(`../docs/cloud-contract.md`) for integration testing the device client.

```bash
python3 cloud_dev_server.py 8000
# device (emulator): baseUrl = http://10.0.2.2:8000/v1
# device (USB/adb reverse): adb reverse tcp:8000 tcp:8000 → http://localhost:8000/v1
```

It verifies per-chunk and whole-file SHA-256, supports resume via
`GET /uploads/{id}`, and advances jobs `QUEUED → PROCESSING → READY` over polls.

**It runs no real pipeline.** Job results are markers/echoes with explicit
`X-Provenance: CLOUD_REFINED` — never fabricated geometry or detections presented
as genuine output. Production replaces this with the FastAPI gateway + S3/PostGIS
+ GPU workers described in the contract.

The Kotlin client (`HttpCloudTransport` + `ChunkedUploader`) speaks this exact
HTTP; `CloudIntegrationTest` proves the uploader's resume/integrity logic, and
the bundled `validate` flow proves the server over real HTTP.
