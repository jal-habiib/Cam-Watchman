# Four-camera resource budget

## Current implementation controls

- The four camera pipelines retain independent capture, recording, frame queues, tracking and event attribution.
- Cameras using the same normalized model path and ONNX thread setting share one reference-counted YOLO11n ONNX session.
- ONNX `run()` calls use a fair, process-wide semaphore with at most two inferences executing at once. Waiting cameras retain independent workers; a slow camera cannot create an unbounded inference queue.
- The current configured inference cadence is 1 frame per second per AI-enabled camera. Each camera worker consumes its latest available frame rather than building a backlog.
- The main-stream preview decoder runs at a minimum of 10 FPS (or the configured AI rate if higher); the AI worker samples that latest-frame tap at its own configured cadence. Preview smoothness is not tied to AI inference rate.
- The shared session currently uses two ONNX intra-op threads. With two simultaneous inferences, the intended upper bound is approximately four ONNX compute threads, leaving CPU capacity for FFmpeg, JavaFX, recording and the operating system.

## Development-PC baseline (21 November 2026)

- CPU: Intel Core i5-8365U, 4 cores / 8 logical processors.
- Physical memory: 7.81 GiB visible to Windows.
- Available memory during the baseline sample: 1.06 GiB.
- The baseline was taken with Cam Watchman not running. Brave had multiple processes using several GiB collectively, Windows Memory Compression used about 765 MiB, and Defender about 270 MiB.
- MediaMTX was not running at the time of the sample, so this was not an application load measurement.

## Initial live four-camera sample (21 November 2026)

The rig and app were started together with all four cameras configured for AI + recording. All eight RTSP paths became ready, all four app runtimes reached ONLINE, and each camera completed its first 30 AI inferences. Only one ONNX model-load log entry appeared for the four runtimes. Last inference timings at the 30-frame checkpoint were approximately 92–125 ms per camera. The synthetic test patterns produced zero person detections, as expected; they do not contain real people.

- Cam Watchman working set was approximately 375 MiB in the first sample, and 292 MiB in the following soak sample.
- The app process used about 1.0 CPU core over a five-second sample.
- System available memory ranged from 0.65 to 0.81 GiB during the early live run (other apps, including multiple browser processes, remained open).
- Total system CPU utilization was about 71–86% during one sample; the eight-process synthetic rig also performs four software substream encodes, so this is not an isolated app CPU measurement.
- Latest ready 15-second MKV segments from all four cameras passed `ffprobe` with exit code 0.

At the 05:20 checkpoint (about 17 minutes after app startup), all four AI workers had reached 960 inferences each. The latest per-camera inference times were approximately 77–125 ms. The log contained 69 finalized segments per camera since this app launch, all four cameras still had an active `writing` row, and no AI, database-writer, queue-full, or camera-reconnect errors had appeared. This demonstrates sustained segment rotation and inference activity so far, not yet a full soak pass.

The preliminary 1-FPS-preview run was stopped after about 28 minutes to correct the display-rate coupling before the formal soak. Each AI worker stopped after approximately 1,670 inferences; each camera finalized 113 segments; the log showed zero AI, database, or reconnect errors. All four final recordings were marked ready, no `writing` rows remained, all four display surfaces had shown about 1,680 frames, and shutdown reported no non-daemon threads. This is useful preliminary evidence, but it is not the required 60-minute acceptance run. The 10-FPS preview change and independent record/AI flag handling now pass the full Maven test suite.

## Corrected 10-FPS preview sample (21 November 2026)

After the preview-rate change, all four main FFmpeg pipelines were verified to run their display decode filters at 10.000 FPS while the AI workers remained at 1 inference per second. Each camera reached its first 30 inferences; warmed inference times were approximately 77–85 ms. The test PC is a Dell Latitude 5400 (Intel i5-8365U, 4 cores / 8 logical processors, 7.8 GiB RAM). The app working set ranged about 338–403 MiB, with 0.87–1.62 GiB system memory available during the early samples. Over a 10-second sample the Java app used about 1.86 CPU cores; the eight-process test rig used another 2.47 cores because it software-encodes the four display substreams. Total system CPU samples ranged about 44–84%. Browser, Discord, Defender and Windows memory compression also occupied significant host resources, so this is not an idle-machine baseline. All four cameras had active `writing` rows and ready recordings, and no AI, DB, or reconnect errors appeared.

The first three samples of the initial formal monitor (later interrupted by host sleep) recorded app working sets of 294.9, 288.9, and 295.8 MiB; system memory available of 1.15, 1.26, and 1.27 GiB; and total system CPU of 56.3%, 68.2%, and 98.3%. All three samples showed one active `writing` row for each camera, ready segments for all four cameras, and zero error lines. The third sample showed that the host can become CPU-saturated under the eight-publisher rig; the host CPU measurement includes four software substream encodes and is not an isolated app measurement. Immediately before that monitor, a live session on 21 November (05:40:17–05:59:49) showed all four cameras ONLINE, each reaching 1,170 AI inferences, while all four continued finalizing 15-second recording segments. The subsequent completed resumed run is documented below. These are development-PC observations, not minimum-hardware certification.

## What is not yet proven

This policy is a guardrail, not a minimum-PC certification. The completed 60-minute run used synthetic RTSP sources on the development PC; it does not establish real-person detection accuracy, minimum-PC requirements, physical-camera compatibility, or clean-Windows-PC installation. Search playback via the external player also still needs UI verification.

## Completed resumed soak (21 November 2026)

The first formal monitor attempt did not complete. The host suspended between samples: the CSV jumped from 06:04:45 to 06:20:40, and all eight FFmpeg publishers reported approximately 898 seconds of input lag followed by RTSP end-of-file / broken-pipe errors. MediaMTX then returned 404 because no publisher remained on the camera paths. The app correctly transitioned all four cameras to RECONNECTING; this is a test-infrastructure interruption, not evidence of an app crash. The partial CSV is retained as `rig/run/four-camera-soak-20261121.csv` and is not counted as a pass.

The rig was restarted and all eight main/sub paths became ready. A fresh monitor ran from 06:23:57 through 07:23:50 in `rig/run/four-camera-soak-20261121-resumed.csv` (59 samples). There were no sample gaps over 75 seconds and zero app/database error lines. Every sample showed an active `writing` row for each of the four cameras; one sample recorded two simultaneous writing rows for camera 4 during segment rotation. Peak app working set was 336.1 MiB and peak private memory was 619.3 MiB. The Windows AC sleep timeout has been restored to 60 minutes. The synthetic rig performs four software substream encodes, so host CPU is not an isolated app measurement.

## Packaged build launch check (21 November 2026)

After the soak, the refreshed 410.1 MB app-image was launched directly from `dist/CamWatchman/CamWatchman.exe` using the rig's four-camera configuration. All four cameras reached ONLINE, all four AI workers logged their first 30 inferences, and recording segments were finalized for all four cameras. This verifies the current packaged app-image startup and core four-camera path on the development PC. It is not a clean-PC installation or physical CCTV test. The refreshed 191.4 MB Inno Setup installer compiled successfully; SHA-256: `2515F3D7CABC1282CBF95DBB5E8D2063295C04BE895D7BDF0974EF590968B784`. The new installer still needs its own isolated install/uninstall smoke test. The Inno Setup 7.1.1 compiler reports non-commercial use only, so this remains an internal test build until appropriate licensing, signing and distribution checks are complete.
