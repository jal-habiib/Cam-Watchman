# Cam Watchman v0.2.0 — Windows Early Access

**Status:** Internal-testing prerelease. Not for production surveillance or safety-critical use.

## What's new since v0.1.0

- Independent processing pipelines for up to four cameras.
- Recording and AI can be enabled independently per camera; the four-camera rig profile enables both on all four.
- Shared ONNX model session and bounded inference scheduling to control concurrent AI work.
- Per-camera detection overlays and event routing.
- Recording queue shutdown now drains accepted metadata commands; database health and queue pressure are exposed.
- Camera reconnect/offline state is visible on the wall.
- Search results exclude clips that are no longer retained; playback actions and search feedback are clearer.
- Search, Zones, Lines, Counts and Cameras navigation overlays remain reachable without losing the camera wall.

## Validation in this build

- Maven unit/integration test suite: 142 tests, 0 failures, 0 errors, 0 skipped (2026-11-23).
- Synthetic four-camera tests and previous multi-camera soak checks passed during development.
- This package is rebuilt from the `v0.2.0` source tree; do not use the older v0.1.0 installer or ZIP for these changes.

## Important limitations

- Internal evaluation only. Physical customer CCTV models, diverse RTSP implementations, clean-PC acceptance and performance across minimum hardware are not yet certified.
- AI accuracy and alerts are experimental; do not rely on them for security, safety or emergency decisions.
- The project has unresolved third-party licensing obligations, including the bundled YOLO model and video-display stack. Do not redistribute this build or use it commercially until those obligations are resolved and the appropriate notices/source materials are supplied.
- The installer is unsigned; Windows SmartScreen may warn.
- Inno Setup compiler use is subject to its installed licence; the local compiler reported non-commercial-use-only terms. This installer is not approved for commercial distribution.

## Downloads

- `CamWatchman-Setup-0.2.0-win64.exe` — Windows x64 installer.
- `CamWatchman-0.2.0-win64.zip` — portable app bundle.
- `SHA256SUMS.txt` — checksums for both downloads.
- `INTERNAL_TESTING_GUIDE-v0.2.0.md` — evaluation and feedback instructions.

Verify the SHA-256 checksum before running either package. Keep camera URLs and credentials private; enter them in the app rather than sharing configuration files.
