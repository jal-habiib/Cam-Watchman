# Cam Watchman

Cam Watchman is a Java desktop CCTV monitoring application built with JavaFX. This is an initial source snapshot of an active-development project, not a production release.

## Current scope

- Four-camera monitoring wall.
- In-app camera management: add, edit, enable, disable and remove cameras, with SQLite-backed configuration.
- RTSP/RTSPS stream support.
- One full AI/recording pipeline on the primary camera; additional panels are display-focused to keep resource use manageable.
- Local object detection and tracking, zone alerts, line-crossing counts, clip search and segmented recording.

## Hardware and validation

The target is a modest Windows laptop (8 GB RAM, no dedicated GPU). The project has been exercised with MediaMTX/FFmpeg test streams and people-bearing sample footage. Controlled single-actor line-crossing tests passed in the development environment. Crowded-scene counting still has known identity/counting limitations.

**Physical CCTV hardware has not yet been validated end-to-end.** Test-stream success is not proof that every camera/NVR model, codec, authentication mode or network configuration will work.

The app currently targets Windows and Java 21. Camera 1 is the only camera intended to run the full AI/recording pipeline; other panels are display-only in the current resource profile.

## Build

Install a 64-bit Java 21 JDK and Maven 3.9+.

    mvn test
    mvn -DskipTests package
    mvn javafx:run

The Maven configuration currently uses the Windows JavaFX native classifier. Cross-platform builds are not yet configured. A local VLC installation may be required for the VLCJ display panels.

ONNX model weights are not included. Obtain a model only after reviewing its licence and place it at the path expected by the application.

## Camera setup and privacy

Add cameras through the in-app Cameras manager. Enter RTSP URLs and credentials locally. Do not commit camera URLs, usernames, passwords, LAN addresses, recordings, databases, logs, screenshots or real footage.

Camera settings, event data and recordings are intended to remain in the local application data directory. Before deploying cameras that record people, check applicable privacy, notice, retention and access-control requirements.

## Project status

Development is ongoing. Known release work includes broader UI-level camera-manager tests, additional runtime acceptance, crowded-scene counting improvements, physical-camera validation, packaging/distribution checks and a final third-party licence review.

See LICENSING_STATUS.md before redistributing or building a public release.
