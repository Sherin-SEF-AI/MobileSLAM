# MapPilot field & soak protocol

Device-side validation that cannot run in CI. Run these on a real Android 12+
device. Pull artifacts via `adb pull $(adb shell echo /sdcard/Android/data/com.mappilot.app.debug/files)`.

## 1. Endurance soak (8h+)

1. Full battery, adequate storage (≥ 32 GB free), cooling-neutral environment.
2. Start a recording from the Capture screen; leave it running 8+ hours.
3. **Expected**: MCAP rolls to new segments at the configured size
   (`trip.mcap`, `trip.0001.mcap`, …); each segment is sealed + flushed on the
   interval; memory bounded (watch `adb shell dumpsys meminfo`); battery drain
   logged each minute (`adb logcat -s MapPilot.service` → "Battery … drain=…%/h").
4. Stop. Verify a single recoverable dataset: every segment opens in the `mcap`
   tooling with a valid index (see `docs/cloud-contract.md` style validation).

## 2. Thermal throttle behaviour

1. Record while loading the device (sun / case / synthetic load) until
   `THERMAL_STATUS_MODERATE`+.
2. **Expected** (Settings → Degradation, live): perception Hz caps, then pauses;
   non-essential render turns off; **recording fps and sync are unaffected**
   (Capture HUD fps stays at 30, sync offsets unchanged). Confirm
   `MapPilot.perception` logs "Degradation: …".

## 3. Crash-recovery soak

1. During recording, kill the app: `adb shell am kill com.mappilot.app.debug`
   (repeat at several points).
2. Relaunch. **Expected**: launch-time recovery finalizes each unsealed MCAP up
   to its last sealed chunk; the broken original is kept as `*.broken`; the
   recovered `trip.mcap` opens with a valid index. (`MapPilot.recording` →
   "Recovered N interrupted MCAP segment(s)").

## 4. 100 GB dataset UI

1. Accumulate (or push) many trips/assets until `files/` exceeds ~100 GB.
2. **Expected**: Sessions + Asset Browser stay responsive — assets page in 200 at
   a time (scroll to load more); storage accounting in Settings reflects usage;
   no full-table load. Storage pressure < 2 GB free sheds perception; < 500 MB
   free stops recording to protect the active file.

## 5. Georeferencing ground-truth

Drive/walk a loop past surveyed points (traffic lights / signs). Compare exported
GeoJSON asset positions to ground truth; expect metre-level agreement where GNSS
+ depth are good. Record GNSS quality (Capture HUD) alongside.

## Acceptance gates (§10/§12)

- Capture→MCAP append off the sensor thread; zero frame drops attributable to disk.
- Sustained 1080p30 + IMU ≥ 100 Hz independent of perception.
- 8h single valid recoverable dataset; bounded memory.
- Thermal: degrade perception/render only; never recording/sync.
- 100 GB UIs responsive; storage accounting correct.
