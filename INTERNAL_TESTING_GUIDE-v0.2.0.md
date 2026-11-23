# Cam Watchman v0.2.0 — Internal testing guide

## Scope

This is an experimental Windows x64 build for controlled evaluation. It is not a production CCTV security system. Do not use it as the sole means of monitoring a site or protecting people/property.

## Install

1. Verify the installer SHA-256 against `SHA256SUMS.txt`.
2. Run `CamWatchman-Setup-0.2.0-win64.exe` as a normal user. The installer is unsigned and Windows may show a SmartScreen warning.
3. Launch Cam Watchman from the Start Menu.
4. Open Cameras and add one camera first. Enter its RTSP main-stream URL; add its substream URL if available.
5. Test the connection, then enable recording and AI as required. Repeat for cameras 2–4.
6. Confirm all expected camera tiles report online before leaving the system unattended.

## Evaluation checklist

- [ ] Install on a clean Windows x64 test account or PC.
- [ ] Add one physical camera and confirm live video.
- [ ] Add up to four cameras and confirm each stream independently.
- [ ] Enable recording on all four; confirm clips finalize and appear in Search.
- [ ] Enable AI on all four; check camera attribution on overlays and alerts.
- [ ] Disconnect and reconnect one camera; confirm its state changes and recovery does not stop other cameras.
- [ ] Search for a retained clip and open playback.
- [ ] Restart the app and verify camera configuration and retained recordings remain.
- [ ] Uninstall only after confirming that recordings and user data remain in `%LOCALAPPDATA%\CamWatchman`.

## Report issues

For each issue, include the app version, Windows version, camera make/model, stream codec, which camera tile was affected, approximate timestamp and steps to reproduce. Redact RTSP usernames, passwords, public IPs and private network details. Never send `cameras.properties`, database files, recordings or snapshots unless specifically requested through a secure channel.

## Known limitations / caution

- Physical-camera compatibility and clean-PC acceptance are still under evaluation.
- Detection and alert accuracy is experimental. False positives and missed detections are possible.
- Do not expose RTSP cameras directly to the public internet.
- Do not redistribute this build or use it commercially until project and third-party licensing obligations are reviewed and cleared.
- The installer is unsigned and built with a locally installed Inno Setup compiler whose current terms report non-commercial use only.
