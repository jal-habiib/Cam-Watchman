# Cam Watchman

Cam Watchman is a Windows desktop CCTV monitoring application built with Java 21 and JavaFX. It provides a four-camera wall, independent per-camera AI-assisted detection and MKV recording, zone alerts, counting lines, and clip search.

## Requirements

- **Windows x64.**
- **FFmpeg** available either on `PATH` or as a static `ffmpeg.exe` in an `ffmpeg\` folder next
  to `CamWatchman.exe` (or the jar). The wall and each enabled camera pipeline spawn FFmpeg for
  RTSP decoding, segment recording and the raw video pipe â€” it is not linked into the Java process.
  The packaged app-image bundles Java 21 and the ONNX model; `scripts/package-appimage.ps1` also
  bundles `tools\ffmpeg\ffmpeg.exe` (or PATH FFmpeg) when one is present.

## Features

- **Four-camera wall:** RTSP video panels using the bundled JavaCV/FFmpeg decoder. No separate VLC installation is required.
- **Live display:** Processing cameras decode a 640x360 preview at a minimum of 10 FPS; AI inference samples independently at its configured rate. Display-only cameras use their substream.
- **AI detection:** Each AI-enabled camera runs its own inference worker and camera-specific overlays/tracking. Workers share one ONNX model session and a fair, two-slot inference scheduler to bound CPU load across four cameras. See [Four-camera resource budget](docs/RESOURCE_BUDGET.md).
- **Event recording:** Each enabled camera can independently record MKV segments and retain segments associated with detection events.
- **Zones and alerts:** configurable restricted zones, intrusion events, snapshots, and in-app alerts.
- **Line crossing:** configurable counting lines with persisted IN/OUT events and totals.
- **Camera management:** add, edit, test, disable, enable, and remove cameras; recording and AI can be toggled independently per camera. See [Camera management](docs/CAMERA_MANAGEMENT.md).
- **Clip search:** search recorded clips and labels from the application.

## Connect your camera (first run)

A fresh copy of Cam Watchman has no camera configured. You need your camera's **RTSP link** â€”
find it in the camera's phone app, its web interface or its manual. It usually looks like:

```text
rtsp://[username:password@]<camera-ip>:<port>/<stream-path>
```

e.g. `rtsp://admin:password@192.168.1.50:554/stream1` or `rtsp://192.168.1.50:8554/live`.

1. Start Cam Watchman. With no camera configured it opens **Cameras** for you (the header
   **Cameras** button re-opens it at any time).
2. Click **+ Add camera**.
3. Give it a name, paste the RTSP link (put any username/password in their own boxes), and
   optionally tick **record** / **AI** for that camera.
4. Click **Test connection** to check the link, then **Save** â€” the wall reloads with your camera.

You can add up to four cameras and **Edit**, **Enable**/**Disable** or **Remove** them later.
Settings live in `%LOCALAPPDATA%\CamWatchman\` (the app's SQLite database is the source of truth;
`cameras.properties` is only the first-run seed), so nothing is tied to the source tree.

Each camera can be display-only, or a full pipeline (display + AI + recording) when you tick
**record** or **AI** on it and give it a main stream. Each enabled camera runs an independent
capture/recording/AI runtime; a camera failure should not stop the others.

## Developer build

Requirements: Windows x64 and Java 21. The project includes a Maven command shim.

\`\`\`powershell
.\mvnw.cmd -q test
.\mvnw.cmd javafx:run
\`\`\`

## Windows packaging

\`\`\`powershell
.\scripts\package-appimage.ps1
.\scripts\package-zip.ps1
.\scripts\package-installer.ps1
\`\`\`

The app-image bundles its Java runtime and ONNX model. The portable ZIP includes per-user install/uninstall scripts. The Inno Setup installer is built with Inno Setup 7.1.1; that compiler reports **Non-commercial use only**. Obtain the appropriate Inno Setup license before commercial distribution.

**Current binaries are internal test builds, not approved public releases.** Third-party licensing and notices (especially the AGPL-3.0 YOLO11n weights), project licensing, code signing, clean-PC installation, and physical CCTV acceptance must be resolved before distribution. See [Licensing and distribution](docs/LICENSING.md).

## Local RTSP test rig

The repository includes a MediaMTX/FFmpeg test rig:

\`\`\`powershell
.\rig\start-rig.ps1
.\rig\status-rig.ps1
.\rig\run-four-camera-soak.ps1 -Minutes 60
.\rig\replace-with-moving-sample.ps1
.\rig\test-reconnect.ps1 -Path cam2_sub
\`\`\`

The rig uses loopback RTSP endpoints under 127.0.0.1:8554. Start Cam Watchman after the rig reports all eight paths ready, then run the soak helper in a separate PowerShell window; it monitors the running app and does not launch or stop it. The soak helper also uses Python 3 (`py`) to sample SQLite recording state. The moving crosswalk sample is for local integration testing, not a production camera source. Stop the rig with .\rig\stop-rig.ps1.

## Acceptance status

The four-camera wall, four independent AI runtimes, four independent recording streams, clip finalization/playability, and single-camera outage/recovery have been exercised on the development PC using synthetic RTSP sources. The full Maven suite passes (142 tests, zero failures). The camera wall now shows per-camera connection details and a reconnect/offline overlay over the video tile; a forced Camera 2 disconnect displayed the overlay, and it cleared when the stream returned ONLINE. Search has explicit Play actions plus date-range validation. Search now filters strictly to retained detection clips, and rechecks a clip's file before opening so expired results are refreshed instead of sent to the player; six SearchService tests and the full 142-test suite pass. A navigation regression was fixed: Search now stays mounted over the workspace so Zones, Lines, Counts and Cameras can open above it. On the rebuilt packaged app, automated Windows UI checks opened all four tools, verified their panels were visible, closed each one back to Search, then returned to the wall and reopened Search successfully. The resumed 60-minute synthetic soak completed on 21 November 2026: 59 samples from 06:23:57 to 07:23:50, no monitoring gaps over 75 seconds, zero application/database error lines, and an active writing row for every camera in every sample. Peak Cam Watchman working set was 336.1 MiB and peak private memory was 619.3 MiB. The first formal attempt was interrupted by host sleep/suspend and is retained only as diagnostic evidence. AC sleep has been restored to its prior 60-minute setting. The latest 410 MB self-contained app-image was then launched directly; all four cameras reached ONLINE, each AI worker completed at least 30 inferences, and all four cameras finalized recording segments. A refreshed 191.4 MB Inno Setup installer was built at dist/CamWatchman-Setup-0.1.0-win64.exe (SHA-256: C0DE1AF719DF3AA7B34FB8DE0AF2D02220CDFA7D46D48D5EAC5F005D5906038A). The refreshed installer passed an isolated silent install/uninstall smoke test: the launcher, bundled FFmpeg, AI model and JVM were present; uninstall removed the test program directory while preserving the existing user database and camera configuration. The installed copy was also launched from its isolated install directory: the bundled YOLO model loaded, all four cameras reached ONLINE, and all four AI workers began inference before the test install was uninstalled cleanly. Search Play was exercised in the rebuilt packaged app: it opened a retained clip in VLC, and a second retained sample decoded cleanly with bundled FFmpeg. One older retained sample reported a corrupt H.264 frame. The moving crosswalk sample was then sent through all four main streams: each camera reached 330 AI inferences with 515–551 detections published, kept clips and person labels were present for every camera, and the latest kept recording from each camera passed strict FFmpeg decoding. This is sample-footage evidence, not physical-camera field acceptance; broad clip-integrity and real-footage acceptance remain open. A two-minute packaged-app moving-footage soak completed (7 samples, zero application/database error lines, all four cameras writing; working set 348.5–391.4 MiB). A follow-up five-minute soak captured 10 samples with all four cameras writing throughout; one transient SQLite busy-snapshot retry occurred, with no permanent batch failure or queue overflow. Real-person detection accuracy, minimum-spec resource use, physical CCTV devices, and a separate clean Windows PC remain unaccepted.

See [moving-footage acceptance](docs/ACCEPTANCE_REAL_FOOTAGE.md) for the tested scope and remaining checks.

## Data and privacy

Camera configuration, SQLite data, recordings, snapshots, and logs are stored in the user's local application-data directory, not in the source repository. Do not commit real camera URLs, credentials, recordings, or databases. Before deploying cameras that record people, check applicable notice, access, retention, and data-protection requirements.
