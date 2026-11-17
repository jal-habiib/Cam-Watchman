# Cam Watchman

Cam Watchman is a Windows desktop CCTV monitoring application built with Java 21 and JavaFX. It provides a four-camera wall, local AI-assisted detection on Camera 1, event recording, zone alerts, counting lines, and clip search.

## Features

- **Four-camera wall:** RTSP video panels using the bundled JavaCV/FFmpeg decoder. No separate VLC installation is required.
- **AI detection:** Camera 1 runs the bundled YOLO11n ONNX model on its main stream and displays detection overlays and track IDs.
- **Event recording:** Camera 1 records MKV segments and retains segments associated with detection events.
- **Zones and alerts:** configurable restricted zones, intrusion events, snapshots, and in-app alerts.
- **Line crossing:** configurable counting lines with persisted IN/OUT events and totals.
- **Camera management:** add, edit, test, disable, enable, and remove camera configurations. See [Camera management](docs/CAMERA_MANAGEMENT.md).
- **Clip search:** search recorded clips and labels from the application.

Camera 1 is the full pipeline (display, AI inference, and recording). Cameras 2–4 are display-only in the current build.

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
.\rig\replace-with-moving-sample.ps1
.\rig\test-reconnect.ps1 -Path cam2_sub
\`\`\`

The rig uses loopback RTSP endpoints under 127.0.0.1:8554. The moving crosswalk sample is for local integration testing, not a production camera source. Stop the rig with .\rig\stop-rig.ps1.

## Acceptance status

The four-panel moving-sample wall, Camera 1 inference, tracking/overlay path, line-crossing persistence, zone alerts, and event clip creation have been exercised on the development PC. Physical CCTV devices and a clean Windows PC have not yet been accepted. The Search view's external-player playback action also needs direct UI acceptance.

See [moving-footage acceptance](docs/ACCEPTANCE_REAL_FOOTAGE.md) for the tested scope and remaining checks.

## Data and privacy

Camera configuration, SQLite data, recordings, snapshots, and logs are stored in the user's local application-data directory, not in the source repository. Do not commit real camera URLs, credentials, recordings, or databases. Before deploying cameras that record people, check applicable notice, access, retention, and data-protection requirements.
