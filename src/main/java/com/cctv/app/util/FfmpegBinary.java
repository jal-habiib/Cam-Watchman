package com.cctv.app.util;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the external {@code ffmpeg} executable used for RTSP decoding and recording.
 *
 * <p>The app shells out to FFmpeg rather than linking it into the JVM, so a packaged build must
 * find a binary. Resolution order: the {@code -Dcctv.ffmpeg=<path>} override, then a bundle next
 * to the app ({@code ffmpeg/ffmpeg.exe} or {@code ffmpeg.exe} beside the launcher/jar), then
 * {@code ffmpeg} on {@code PATH}. This lets a distributed image be self-contained instead of
 * depending on a developer machine's FFmpeg install.
 */
public final class FfmpegBinary {

    private FfmpegBinary() {
    }

    /** Absolute path to a bundled ffmpeg when present, otherwise the plain command name. */
    public static String resolve() {
        String explicit = System.getProperty("cctv.ffmpeg");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        try {
            Path code = Path.of(FfmpegBinary.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            Path dir = Files.isDirectory(code) ? code : code.getParent();
            for (Path base : new Path[] {dir, dir == null ? null : dir.getParent()}) {
                if (base == null) {
                    continue;
                }
                Path bundled = base.resolve("ffmpeg").resolve("ffmpeg.exe");
                if (Files.isRegularFile(bundled)) {
                    return bundled.toString();
                }
                bundled = base.resolve("ffmpeg.exe");
                if (Files.isRegularFile(bundled)) {
                    return bundled.toString();
                }
            }
        } catch (Exception ignored) {
            // fall through to PATH
        }
        return "ffmpeg";
    }
}
