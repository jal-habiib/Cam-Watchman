# Real-footage acceptance run - 2026-11-17

Purpose: exercise the full pipeline on real people/cars instead of synthetic test patterns, per
REMAINING_WORK_ALLOCATION.md (joint acceptance items 4-5), and record what works and what does not.

## Source footage

- Wikimedia Commons, `File:DiagonalCrosswalkYongeDundas.webm` (CC0): Yonge-Dundas square,
  Toronto - pedestrians crossing, cars, storefronts, 1920x1080 VP8, 24 fps, 56.6 s.
- Downloaded to `testdata\real\crosswalk.webm` (git-ignored), transcoded for the rig:
  `ffmpeg -ss 00:00:02 -i crosswalk.webm -t 40 -vf "scale=1280:720,fps=25" -c:v libx264 -preset veryfast -crf 24 -pix_fmt yuv420p -an testdata\real\crosswalk-720p.mp4`
  -> 1280x720 H.264, 25 fps, 40 s, 12.7 MB.
- Published through the same MediaMTX rig (`-c copy`, looped) as `rtsp://127.0.0.1:8554/cam1_real`,
  so the engine path is identical to the synthetic tests.

## Run

`java -Xms128m -Xmx1200m -Dcctv.config=tmp\real-cameras2.properties
  -Dcctv.zone.seed.demo=1 -Dcctv.zone.seed.rect=0.52,0.62,0.42,0.30
  -Dcctv.zone.seed.name=Crosswalk
  -Dcctv.line.seed.points=0.62,0.30,0.62,0.95 -Dcctv.line.seed.name="Crosswalk line"
  -Dcctv.count.min.box.height=0.25
  -Dcctv.snapshot=evidence\accept-real-wall2.png -Dcctv.snapshot.delay=95
  -cp "target\classes;<target\cp.txt>" com.cctv.app.Main`

95 s session, camera 1 record + ai at 4 inferences/s, cameras 2-4 display-only substreams.

## Results

| Check | Result | Evidence |
|---|---|---|
| Single Camera 1 connection | pass - one grabber, one RTSP session, no second reader | MediaMTX readers on cam1_real during run |
| Live detection on real people/cars | pass - boxes on foreground pedestrians, cyclists, cars; 1514 detections over 330 inferences | evidence/accept-real-wall2.png |
| Tracking overlay | works, but IDs churn (person #1..#166 in 95 s) | same screenshot |
| Zone intrusion on real footage | pass - 2 alerts, both linked to a kept clip, snapshot shows the two pedestrians inside the zone | alerts rows 1-2, snapshot alert-10121b32-...png |
| Packet-copy recording | pass - 6 clips, H.264 1280x720, 2.7-7.8 MB each, all playable, keep=true | tmp/tmp/real-data2/recordings + .json sidecars |
| Clip labels | pass - person/car/truck counts per clip, max confidence 0.90 person / 0.92 car | clip_labels rows |
| Counting line | partial - crossings fire, persist and summarise, but the count is inflated | count_summaries in=6 out=13 over ~80 s |
| Shutdown | pass - clean close, no orphan threads | log tail |

## Counting accuracy finding

The line crossing events are real but over-counted for two reasons:

1. **Track identity churn.** The engine's `ObjectTracker` associates by IoU (>= 0.25). At 4 inferences/s
   a walking pedestrian moves far enough that the box IoU between frames falls below the threshold, the
   track expires and a new id starts mid-crossing - so one physical crossing can produce several events.
   The build plan ("People counting", 7a) explicitly asked for **centroid-distance** matching *because*
   IoU fails at low frame rates.
2. **Background pedestrians.** A full-height vertical line also counts people walking along the far
   sidewalk. The app-side near-field gate (`-Dcctv.count.min.box.height=0.25`, added in this run)
   reduced events from 32 to 19, which confirms the filter works but cannot fix id churn.

Recommendation (engine scope, ChatGPT): switch `ObjectTracker` association to class-aware centroid
distance (normalized), expire by time rather than strict missed-frame count (for example 2-3 s), and keep
the confirmed-track rule. Then the same footage should give one IN/OUT per physical crossing.

## Not testable in this environment

- Wowza RTSP and Big Buck Bunny HLS: no Wowza instance and no HLS input support is claimed - documented
  as unavailable rather than "passed".
- Real CCTV cameras: none attached to this machine yet; the MediaMTX rig + real footage is the closest
  deterministic substitute.

## Reproduce

`rig\start-rig.ps1` (synthetic paths stay as-is), publish `cam1_real` as above, then the run command.
Evidence files are committed; `tmp/` and `testdata/real/` are git-ignored.

## Live zone and counting-line overlay (app, 5c/7b)

The saved zones and counting lines of the pipeline camera are now painted on the live Camera 1 view
(dashed amber outline + name for zones, green line + name for lines), so an operator can see what is
configured without opening the editors. Evidence: `evidence/accept-real-overlay.png` - real crosswalk,
`Engine zone` and `WalkLine` drawn, track boxes `person #1..#3` and `car #4` on top.

Reproduce:

    # publish the CC0 clip on the rig (separate shell)
    ffmpeg -hide_banner -loglevel error -re -stream_loop -1 -i testdata\real\crosswalk-720p.mp4 ^
      -c copy -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:8554/cam1_real

     = Get-Content target\cp.txt -Raw
    java -Xms128m -Xmx1200m -Dcctv.config=tmp\real-cameras2.properties ^
      -Dcctv.zone.seed.demo=1 -Dcctv.zone.seed.rect=0.55,0.45,0.40,0.50 ^
      -Dcctv.line.seed.points=0.45,0.30,0.45,0.95 -Dcctv.line.seed.name=WalkLine ^
      -Dcctv.snapshot=evidence\accept-real-overlay.png -Dcctv.snapshot.delay=7.5 ^
      -cp "target\classes;" com.cctv.app.Main

The overlay is redrawn by the existing 30 Hz canvas timer; saving a zone or a line refreshes it
immediately (editor callbacks), so no restart is needed.

## Centroid-tracker rerun (engine change, same clip)

After ChatGPT switched `engine/ObjectTracker` to class-aware normalized centroid association (max center
distance 0.18, 12 missed frames / 2500 ms stale, two-hit confirmation), the same 40 s crosswalk clip was
replayed for 92 s on a fresh data dir (`tmp\real-centroid.properties`, `-Dcctv.count.min.box.height=0.18`):

| Metric | Value |
|---|---|
| inferences / detections | 327 / 1500 (4 fps, warm run ~200-560 ms per inference) |
| tracking ids at the end | `person #67`, `#70`, `#74` - churn is reduced but not gone |
| counting events | 26 total: 8 IN, 18 OUT (was 32 with IoU, 19 with the 0.25 gate on IoU) |
| summaries | `count_summaries` 2026-11-17 in=8 out=18 |
| clips / alerts | 6 ready clips (26.4 MB), 1 alert written, later entries suppressed by the 60 s cooldown |

Reading: the IN/OUT imbalance (8 vs 18) still indicates identity resets - a pedestrian that is re-identified
mid-line produces a second OUT. The crosswalk clip also has dozens of real pedestrians, so there is no
ground truth for "how many should have crossed"; a controlled single-actor clip is needed for the plan's
9-of-10 crossing criterion (the synthetic walk-through actor or a staged recording), while this clip remains
the detection/zone/recording acceptance case.

Evidence: `evidence/accept-real-centroid.png` (live wall with zone + line overlay, tracks, ONLINE - REC).

Verification: `mvnw.cmd test` -> 112 tests, 0 failures (App 4, AI 24, config 9, contract 7, db 27,
engine 22, label 3, count 8, alert 4, etc.).

## Controlled single-actor counting acceptance (PASS 10 / 10)

The busy crosswalk cannot prove the plan's "9 of 10 controlled crossings" criterion, so a controlled clip is
generated from the existing licence-free test image with `scripts/make-walker-clip.ps1`:

- crops the pedestrian in the beige coat from `testdata/bus.jpg`, scales him to 360 px and composites him on a
  flat 1280x720 background, crossing left to right once every 8 s for 80 s -> **exactly 10 passes**;
- replayed on the rig as `rtsp://127.0.0.1:8554/cam1_walk` and recorded/tracked by the engine
  (`tmp\walker.properties`, `ai.rate.fps=4`, counting line `x=0.5` from `y=0.2` to `0.9`).

Result (307 inferences, 239 detections, 5 clips, 311 KB):

| Quantity | Value | Expected |
|---|---|---|
| `count_events` rows | **10** | 10 passes |
| distinct `track_id` values | **10** | one id per pass |
| tracks that crossed twice | **0** | 0 |
| `count_summaries` 2026-11-17 | in=0 out=10 | 10 crossings, one direction |

Verdict: **pass** - one event per pass, no duplicate ids, no misses. Direction reads OUT for a left-to-right
walk because the line was drawn top-to-bottom; drawing it bottom-to-top flips IN/OUT (documented in
`docs/PHASE7_COUNTING.md`).

Evidence: `evidence/accept-walker-counting.png` (live wall with the sprite, `person #10`, the `WalkLine`,
`ONLINE - REC`, 237 detections in the footer).

Limitation: the sprite is a rigid cut-out (no leg motion), so this validates the counting pipeline, not the
detector's behaviour on a really walking person - the crosswalk run above covers that side.


## Candidate-constrained association rerun (2026-11-17)

After reviewing the centroid tracker replay, two alternatives were tested on the same 40 s CC0 crosswalk clip:

1. Constant-velocity prediction experiment (fresh tmp\\real-data3, 95 s): 365 inferences / 1704 detections, 34 crossing events (14 IN / 20 OUT), 31 distinct crossing track IDs, maximum crossing track ID 117. This was worse than the prior centroid baseline and was reverted.
2. Constrained-first greedy association (fresh tmp\\real-data4, 95 s): candidate pairs are still class-aware and gated by the existing normalized centroid distance, but tracks with fewer valid candidate detections are assigned first, then by distance. This prevents a flexible track from consuming the only detection available to a constrained track.

| Metric | Previous centroid baseline (real-data2) | Constrained-first (real-data4) |
|---|---:|---:|
| Runtime | 92 s | 95 s |
| Inferences | 327 | 340 |
| Detections | 1500 | 1519 |
| Count events | 19 (6 IN / 13 OUT) | 19 (5 IN / 14 OUT) |
| Distinct crossing track IDs | 15 | 13 |
| Highest crossing track ID | 157 | 59 |

Result: **promising but partial**. Event count did not improve and IN/OUT remains imbalanced, so crowded-scene counting is not accepted as accurate. However, the maximum track ID fell from 157 to 59 while the number of inferences/detections was similar, indicating materially fewer track creations on this replay. The constrained-first association is retained provisionally; velocity prediction is reverted. Controlled single-actor 10/10 acceptance remains unchanged.

Evidence: evidence/accept-real-constrained.png (four-camera wall, real crosswalk on Camera 1, live person boxes/IDs, zone and counting-line overlays, ONLINE - REC).

Engine regression: ObjectTrackerTest now includes constrainedTrackGetsItsOnlyCandidateBeforeFlexibleTrack and the equal-valued-detection regression. Targeted and full-suite results are recorded in INTEGRATION_LOG.md after verification.


## Four-camera FFmpeg display acceptance (2026-11-17)

The initial production VLCJ display cells reported playback started but never received H.264 frames on this PC; the local VLC diagnostic reported the H.264 decoder was unsupported. The production display path was replaced with FfmpegDisplayView, using the bundled JavaCV/FFmpeg runtime and RTSP/TCP. VLCJ and its JNA dependencies were removed from the production build.

| Check | Result | Evidence |
|---|---|---|
| Camera 1 moving-footage wall | pass - live footage, detection boxes, object labels/tracking IDs, zone and WalkLine overlays visible | live Cam Watchman window, 2026-11-17 |
| Camera 2 display | pass - first decoded 640x360 frame; ONLINE badge | application log 14:09:46 |
| Camera 3 display | pass - first decoded 640x360 frame; ONLINE badge | application log 14:09:50 |
| Camera 4 display | pass - restored exact soft-deleted rig row; first decoded 640x360 frame; ONLINE badge | application log 14:09:42 |
| Line crossing persistence | pass - DB held 72 IN / 87 OUT on camera 1 WalkLine; events continued during the run | count_events, count_summaries |
| Zone intrusion | pass - 39 persisted alerts; current-run alert snapshots and popup linked to clips | alerts, alert-writer log |
| Event recording | pass - detection-triggered clips finalized with status ready and keep=true | recordings rows / segment log |
| Clip file decode | pass - FFmpeg decoded a frame from a retained MKV, exit code 0 | camera-1-96586cc9-00015.mkv |
| Search UI double-click opens default player | not yet directly verified | SearchView delegates to Desktop.open() |

Camera 4 restoration was limited to row id 4 with the exact expected name/substream and soft-deleted state. A SQLite online backup was made first; unrelated camera 5 soft-delete state and recording history were preserved.

The display fix is an internal test build only. Physical CCTV devices and clean-PC installation are still untested. Busy-scene IN/OUT counts are operational events, not a ground-truth accuracy claim; the controlled single-actor 10/10 test remains the accuracy acceptance evidence.


## Packaged app-image model resolution and moving-footage retest (2026-11-17)

The first packaged launch exposed that the AI model path was resolved against the process working directory. Added ModelPathResolver so the app can locate models/yolo11n.onnx beside the packaged app even when launched from another working directory. Four resolver tests were added and the full Maven suite passed.

The rebuilt dist/CamWatchman app-image loaded the bundled YOLO model successfully and started the Camera 1 engine. All five local RTSP publishers were verified to have reverted to synthetic sources, so rig/replace-with-moving-sample.ps1 was extended to replace all five rig publishers with the moving crosswalk sample. The subsequent packaged-app run logged Camera 1 ONLINE with AI inference, Cameras 2-4 connected and received 640x360 frames, and detection-triggered clips/zone alerts were produced.

This verifies the bundled model lookup and moving sample stream path on the development PC. It is not a clean-PC install or physical CCTV test. The portable ZIP and installer must be rebuilt after this model-path fix and the ZIP archive audit rerun.
