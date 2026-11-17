# Licensing and distribution recommendation

Scope: the current app-image bundles Java, JavaFX, JavaCV's FFmpeg natives, ONNX Runtime, SQLite,
logging, and YOLO11n weights. Cameras 2-4 use the bundled JavaCV/FFmpeg decoder; VLCJ and libvlc are
no longer production dependencies. Status: internal testing only - this is not legal advice.

## What is actually in the build

| Component | Version | License (upstream) | In the image? | Distribution consequence |
|---|---|---|---|---|
| Temurin JDK runtime | 21.0.12.1 | GPLv2 + Classpath Exception | yes | fine to bundle; source offer for the runtime |
| JavaFX | 21.0.2 | GPLv2 + Classpath Exception | yes | fine to bundle; notices |
| JavaCV | 1.5.14 | GPLv2 + Classpath Exception (Java side) | yes | linking is the intended use; notices |
| FFmpeg natives (bytedeco preset) | 8.1.2-1.5.14 | LGPL-3.0-or-later (packaged DLL config inspected) | yes (jar) | LGPL notices/source and dynamic-linking obligations |
| ONNX Runtime | 1.32.0 | MIT | yes | fine with notice |
| sqlite-jdbc | 3.54.1.0 | Apache-2.0 | yes | fine with notice |
| SLF4J / Logback | 2.0.17 / 1.5.38 | MIT / EPL-1.0+LGPL-2.1 | yes | fine with notices |
| VLCJ / libvlc | — | not used by current production build | no | no external VLC prerequisite |
| YOLO11n weights (`models\yolo11n.onnx`) | Ultralytics export | **AGPL-3.0** unless a commercial Ultralytics license exists | yes | **the biggest constraint**: AGPL for a distributed product |
| MediaMTX | v1.21.4 | MIT | no (dev rig only) | not distributed |
| Real test footage | Wikimedia CC0 | CC0 | no (testdata is git-ignored) | no obligation |

Local FFmpeg facts: the PATH `ffmpeg.exe` development tool is `--enable-gpl --enable-version3` with
libx264/libx265, i.e. GPLv3; it is not copied into the app-image. The packaged Bytedeco native artifact was
separately inspected via its bundled `avcodec-62.dll` configuration string: FFmpeg 8.1.2 reports
`libavcodec license: LGPL version 3 or later`, with `--enable-gpl`, `libx264`, and `libx265` absent.
See the packaged-artifact evidence below. The shipped FFmpeg is therefore LGPL-3.0-or-later, subject to
preserving its notices, source availability and dynamic-linking/relink rights.

## Options

**A. Ship as GPLv3 (leave the stack as it is).**
Publish the complete corresponding source of the app under GPLv3, include FFmpeg license texts and a
written offer for their source, and keep the Ultralytics AGPL obligation in mind (AGPL is compatible with
GPLv3 for combination, but the model's AGPL terms extend to network use). Effort: low engineering, real
legal/process work; the code is local-only today, so publishing it is a product decision.

**B. Permissive stack (recommended if the app will be a closed product).**
1. Replace the detector weights with a permissive model (YOLOX-Nano, NanoDet or MobileNet-SSD are
   Apache-2.0/MIT and export to ONNX). Keep the `YoloPreprocessor`/`YoloPostprocessor` contract, only the
   decode layout changes (confirmed by the existing shape check at startup).
2. Build/bundle an LGPL FFmpeg (no `--enable-gpl`, no x264). Recording is packet copy and the rig publishes
   H.264 from OBS/cameras, so no GPL encoder is needed at runtime; the H.264 decoder is LGPL.
3. Cameras 2-4 now use the bundled JavaCV/FFmpeg display decoder; no VLC/libvlc install is required.
4. Add `THIRD_PARTY_NOTICES.md` generated from `pom.xml` + jar manifests and ship it in the app-image.
Effort: a few days of integration + a licensing review; keeps the current architecture.

**C. Internal only for now.** Treat `dist\CamWatchman` as a test build, do not hand it to third parties,
and defer A/B until the product decision is made. This is where we are today.

## Recommendation

Stay on C while the feature set is still moving. Before any external distribution, take B (permissive model
+ LGPL FFmpeg without an external VLC prerequisite) because it keeps the architecture and avoids republishing the app source;
choose A only if the product is meant to be open source anyway.

## Checklist before the first external build

- [x] Confirm the packaged Bytedeco FFmpeg license from the bundled DLL configuration string: LGPL-3.0-or-later (2026-11-17).
- [ ] Decide the detector model license and swap if needed; re-run the 0c/2e checks after the swap.
- [ ] Add `THIRD_PARTY_NOTICES.md` + license texts to the app-image input.
- [x] Remove VLCJ/libvlc from the production build; cameras 2-4 use bundled JavaCV/FFmpeg (2026-11-17).
- [ ] Record the decision in INTEGRATION_LOG.md with the version pins used for the build.

## Packaged-artifact evidence (added 2026-11-17, answers ChatGPT's question)

The PATH `ffmpeg.exe` (winget/yt-dlp build, reported via `-L`) is **not** what ships. The app uses the
Bytedeco native jar `org.bytedeco:ffmpeg:8.1.2-1.5.14:windows-x86_64` (declared in `pom.xml`, copied into
`lib\` by `scripts/package-appimage.ps1`). The configuration string embedded in that jar's own
`avcodec-62.dll` was extracted and reads:

`
--enable-shared --enable-version3 --enable-runtime-cpudetect --enable-zlib
--enable-libmp3lame --enable-libspeex --enable-libopencore-amrnb --enable-libopencore-amrwb
--enable-libvo-amrwbenc --enable-openssl --enable-libopenh264 --enable-libvpx --enable-libfreetype
--enable-libharfbuzz --enable-libopus --enable-libxml2 --enable-libsrt --enable-libwebp --enable-libaom
--enable-libsvtav1 --enable-libzimg --enable-vulkan --enable-hwaccel=h264_vulkan --enable-hwaccel=hevc_vulkan
--enable-hwaccel=av1_vulkan --enable-cuda --enable-cuvid --enable-nvenc --enable-libmfx --enable-w32threads
--enable-indev=dshow --target-os=mingw32
libavcodec license: LGPL version 3 or later
FFmpeg version 8.1.2
`

Key points:

- `--enable-gpl` is **absent** and no `libx264`/`libx265` is linked, so the shipped FFmpeg is
  **LGPL-3.0-or-later**, not GPL. `--enable-version3` upgrades the LGPL to v3.
- Obligations that follow: keep the FFmpeg libraries dynamically linked (the jar ships DLLs), reproduce the
  LGPL notice, state that FFmpeg is unmodified Bytedeco build 8.1.2 with upstream source available at
  ffmpeg.org, and do not forbid reverse engineering for debugging relinks.
- Audio codecs present: libmp3lame (LGPL), libopus (BSD), libvorbis absent, libaom (BSD), libsvtav1 (BSD),
  libvpx (BSD), openh264 (BSD), OpenSSL (Apache-2.0) - all compatible with an LGPLv3 library.
- The PATH `ffmpeg.exe` (GPLv3 with libx264/libx265) is a **development tool only**; it is never copied into
  `dist\CamWatchman`.

Remaining distribution blockers are therefore not FFmpeg but:

1. **VLCJ/libvlc removed from the production build** - no external VLC prerequisite remains for cameras 2-4.
2. **YOLO11n weights are AGPL-3.0** - requires open-sourcing the service or a different, permissively licensed
   detector (e.g. NanoDet/YOLOX-Nano, MIT/Apache).
3. **Display path**: cameras 2-4 use the bundled JavaCV/FFmpeg decoder; no VLC prerequisite remains.


## Installer tool licensing (2026-11-17)

The locally installed Inno Setup 7.1.1 compiler printed "Non-commercial use only" during both successful installer builds. Do not use this compiler/build path for commercial distribution unless the appropriate Inno Setup commercial license is obtained. This is separate from the app's third-party library/model licensing and does not make the current installer cleared for release.
