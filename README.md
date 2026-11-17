# Cam Watchman — Java CCTV Dashboard

Four-camera JavaFX CCTV dashboard for an 8 GB, no-GPU laptop. **Camera 1** runs the full
pipeline: one JavaCV `FFmpegFrameGrabber` on the main stream feeds display, AI inference and
packet-copy recording. **Cameras 2–4** are display-only VLCJ callback surfaces on substreams.
Alerts for zone intrusion are in-app (popup + sound), footage is stored as 10–30 s MKV segments
that are kept when a detection overlaps them.

Source of truth: *Java CCTV Dashboard: Final Build Plan* (11 pp.) plus the shared documents in
this folder. Two agents work here — ChatGPT owns the engine, DeepSeek owns app + infrastructure —
see [TWO_AGENT_WORK_SPLIT.md](TWO_AGENT_WORK_SPLIT.md) and [INTEGRATION_LOG.md](INTEGRATION_LOG.md).

## Build and run

```bat
.\mvnw.cmd -q -DskipTests compile     :: compile
.\mvnw.cmd -q test                     :: unit tests
.\mvnw.cmd javafx:run                  :: run the app (spike UI for now)
```

`mvnw.cmd` is a shim, not the standard wrapper: this machine has no `mvn` on PATH but has Maven
3.9.16 under `%USERPROFILE%\.m2\wrapper\dists`. It uses a repo-local `.m2repo` (git-ignored).

Environment: Java 21 (Temurin 21.0.12.1), JavaFX 21.0.2, JavaCV 1.5.14 (ffmpeg windows-x86_64),
VLC 3.0.20 + vlcj 4.12.1, ONNX Runtime 1.32.0, SQLite JDBC. FFmpeg is on PATH. HTTPS through
PowerShell/curl fails on this box (`SEC_E_NO_CREDENTIALS`); Java TLS and Python `urllib` work, so
Maven resolves normally.

## Test rig (fake cameras)

```powershell
powershell -ExecutionPolicy Bypass -File rig\start-rig.ps1     # MediaMTX + 5 publishers, waits until ready
powershell -ExecutionPolicy Bypass -File rig\status-rig.ps1    # readers per path (should be ≤1 per profile)
powershell -ExecutionPolicy Bypass -File rig\test-reconnect.ps1 -Path cam2_sub
powershell -ExecutionPolicy Bypass -File rig\stop-rig.ps1
```

| URL | Profile |
|---|---|
| `rtsp://127.0.0.1:8554/cam1_main` | 1280x720@25, H.264, packets copied |
| `rtsp://127.0.0.1:8554/cam1_sub` … `cam4_sub` | 640x360@10, H.264 |

Details: [docs/PHASE0_0E_TEST_RIG.md](docs/PHASE0_0E_TEST_RIG.md). MediaMTX itself is git-ignored
in `tools/mediamtx/` (v1.21.4, Windows amd64).

## Layout and ownership

| Path | Owner | Contents |
|---|---|---|
| `src/main/java/com/cctv/contract/**` | **joint, frozen** | `FrameSource`, `Frame`, `Detection`, `EventBus`, `SnapshotProvider` (see `DEEPSEEK_TO_CHATGPT_INTERFACE_REVIEW.md` for v0.2) |
| `src/main/java/com/cctv/engine/**` | ChatGPT | grabber/stream manager, packet-copy recorder, AI pipeline, zone decision |
| `src/main/java/com/cctv/app/**` | DeepSeek | JavaFX shell, config, VLCJ wall, SQLite, retention, labels/search, alerts |
| `src/main/java/com/cctv/spike/**` | shared | Phase 0 spikes (`wall/` = 0d, `JavaCvSpike*` = 0a, `SegmentCopySpike` = 0b) |
| `rig/**`, `scripts/**` | DeepSeek | MediaMTX rig, launchers |
| `docs/**`, `evidence/**` | shared | spike write-ups and measured evidence |
| `pom.xml`, `mvnw.cmd` | DeepSeek | build; engine requests dependency changes |
| `cameras.properties` | — | **git-ignored** real camera URLs; template is `cameras.properties.example` |

Two-owner rule: one owner per file. A change to `com/cctv/contract/**` needs the other agent's
acknowledgement logged in `INTEGRATION_LOG.md`.

## Status

| Phase | Item | Owner | State |
|---|---|---|---|
| 0a | JavaCV single source → ImageView | ChatGPT | not started |
| 0b | packet copy → playable MKV | ChatGPT | not started |
| 0c | YOLO11n ONNX on a still | ChatGPT | not started |
| 0d | VLCJ ×4 CPU/RAM | DeepSeek | **passed** — 12.3 % CPU / 293 MB, see `docs/PHASE0_0D_VLCJ.md` |
| 0e | MediaMTX + FFmpeg test rig | DeepSeek | **passed** — see `docs/PHASE0_0E_TEST_RIG.md` |
| 1b | config load/validate/redact, base data dir | DeepSeek | **done** |
| 1c | health badges (Online/Offline/Recording) | DeepSeek | **done** — five-state contract badge, see docs/CONTRACT_V02.md |
| 3c | retention, three-step delete, reconcile, disk guards | DeepSeek | **done** — docs/PHASE3C_STORAGE.md |
| 4c | clip search by object + date range | DeepSeek | **done** — docs/PHASE4C_SEARCH.md |
| 4a/4b | clip labels on finalize + JSON sidecar | DeepSeek | **done** — labels in `clip_labels`, `<clip>.mkv.json` atomic, docs/PHASE4_LABELS.md |
| 5a/5c | zone storage + click-drag editor on a frozen frame | DeepSeek | **done** — docs/PHASE5A_5C_ZONES.md |
| 5b | zone confirmation (feet point, 2 consecutive, 60 s cooldown) | DeepSeek (placeholder) | **done app-side** — engine ownership pending, docs/PHASE5B_ZONE_CONFIRM.md |
| 5d | intrusion alert: snapshot, alerts row, popup, sound | DeepSeek | **done** — docs/PHASE5D_ALERTS.md |
| 6a/6b | live VLCJ wall for cameras 2-4, 1x1/2x2 toggle | DeepSeek | **done** — docs/PHASE6A_WALL.md |
| 6c/P6 | Camera 1 display tap + detection overlay, wall resource budget | DeepSeek | **done** — 450 MB / 9.6 % CPU, docs/PHASE6_RESOURCES.md |
| 8d | secret scan, git-ignored credentials | DeepSeek | **done** — `scripts/check-secrets.ps1` clean |
| 8a/8c/8e | shutdown audit, rotating logs, jpackage app-image | DeepSeek | **done** (installer needs WiX) — docs/PHASE8_HARDENING.md |
| 8f | stability run | DeepSeek | **30 min soak passed** (450 MB peak, 7.5 % CPU, flat) — docs/PHASE8F_SOAK.md; 8 h run in progress from the packaged app-image |
| 7a | tracking display (`person #12`) | DeepSeek | **done** — engine `TrackingUpdate`, docs/PHASE7A_TRACKING.md |
| 7b | counting line editor + persistence (`count_lines`) | DeepSeek | **done** — docs/PHASE7_COUNTING.md |
| 7c | crossing storage + counts panel (`count_events`/`count_summaries`) | DeepSeek | **done** — docs/PHASE7_COUNTING.md |

## Data and privacy

Recordings, the SQLite DB, logs and `cameras.properties` live under a configurable base data
directory (default: the user's local app-data folder), never in the repo. Credentials never reach
logs; URLs are redacted before they are printed. Before deploying anywhere that records people,
check local data-protection rules on notice, retention and access.

| 8g | camera management (add/edit/disable/remove, RTSP test, persistence) | DeepSeek | **done** — docs/CAMERA_MANAGEMENT.md |
