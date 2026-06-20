#!/usr/bin/env python3
"""
MapPilot dev reference server (see ../docs/cloud-contract.md).

A zero-dependency stdlib implementation of the cloud contract for integration
testing. Production uses a FastAPI gateway + S3/PostGIS/GPU workers; this server
verifies checksums and returns REAL lifecycle states, but runs NO real pipeline —
its job results are markers/echoes with explicit provenance, never fabricated
geometry or detections presented as genuine output.

Run:  python3 cloud_dev_server.py [port]   (default 8000)
"""
import hashlib
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPLOADS = {}   # uploadId -> {tripId,totalBytes,chunkSize,sha256,chunks:{i:bytes},complete}
JOBS = {}      # jobId -> {polls, artifactId, type}
_seq = [0]


def _id(prefix):
    _seq[0] += 1
    return f"{prefix}_{_seq[0]}"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # quiet
        pass

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(n) if n else b""

    def _path(self):
        return self.path.split("?")[0].removeprefix("/v1")

    def do_POST(self):
        p = self._path()
        if p == "/uploads":
            req = json.loads(self._body() or b"{}")
            uid = _id("u")
            UPLOADS[uid] = {**req, "chunks": {}, "complete": False}
            return self._json(200, {"uploadId": uid, "chunkSize": req["chunkSize"], "receivedChunks": []})
        if p.startswith("/uploads/") and p.endswith("/complete"):
            uid = p.split("/")[2]
            u = UPLOADS.get(uid)
            if not u:
                return self._json(404, {})
            total = -(-u["totalBytes"] // u["chunkSize"])
            if len(u["chunks"]) < total:
                missing = [i for i in range(total) if i not in u["chunks"]]
                return self._json(409, {"missingChunks": missing})
            md = hashlib.sha256()
            for i in sorted(u["chunks"]):
                md.update(u["chunks"][i])
            if md.hexdigest() != u["sha256"]:
                return self._json(409, {"error": "whole-file checksum mismatch"})
            u["complete"] = True
            return self._json(200, {"artifactId": f"a_{uid}", "state": "COMPLETE"})
        if p == "/jobs":
            req = json.loads(self._body() or b"{}")
            jid = _id("j")
            JOBS[jid] = {"polls": 0, "artifactId": req.get("artifactId"), "type": req.get("type")}
            return self._json(200, {"jobId": jid, "state": "QUEUED"})
        return self._json(404, {})

    def do_PUT(self):
        p = self._path()
        parts = p.split("/")
        if len(parts) == 5 and parts[1] == "uploads" and parts[3] == "chunks":
            uid, idx = parts[2], int(parts[4])
            u = UPLOADS.get(uid)
            if not u:
                return self._json(404, {})
            data = self._body()
            declared = self.headers.get("X-Chunk-SHA256")
            if hashlib.sha256(data).hexdigest() != declared:
                return self._json(409, {"index": idx, "received": False})
            u["chunks"][idx] = data
            return self._json(200, {"index": idx, "received": True})
        return self._json(404, {})

    def do_GET(self):
        p = self._path()
        parts = p.split("/")
        if len(parts) == 3 and parts[1] == "uploads":
            u = UPLOADS.get(parts[2])
            if not u:
                return self._json(404, {})
            total = -(-u["totalBytes"] // u["chunkSize"])
            return self._json(200, {"uploadId": parts[2], "receivedChunks": sorted(u["chunks"]),
                                    "totalChunks": total, "state": "UPLOADING"})
        if len(parts) == 4 and parts[1] == "jobs" and parts[3] == "result":
            j = JOBS.get(parts[2])
            if not j or j["polls"] < 3:
                return self._json(409, {})
            body = b"refined-result-marker (dev server: no real pipeline)"
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("X-Provenance", "CLOUD_REFINED")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if len(parts) == 3 and parts[1] == "jobs":
            j = JOBS.get(parts[2])
            if not j:
                return self._json(404, {})
            j["polls"] += 1
            state = "READY" if j["polls"] >= 3 else "PROCESSING"
            return self._json(200, {"jobId": parts[2], "state": state, "progress": min(1.0, j["polls"] / 3),
                                    "provenance": "CLOUD_REFINED", "resultUrl": None, "error": None})
        return self._json(404, {})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    print(f"MapPilot dev cloud server on http://localhost:{port}/v1  (no real pipeline)")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
