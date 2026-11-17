package com.cctv.spike;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spike 0b: packet-copy recording. No decode, no re-encode: compressed packets go from the
 * grabber straight into MKV segments, cut on the first keyframe at or after the segment
 * boundary (a segment always starts with a keyframe, otherwise it cannot be played).
 *
 * <p>Exit criteria: a 60 s source produces ~15 s MKV segments, each one decodable by ffprobe
 * with matching codec/resolution, and per-segment packet counts are sane.
 *
 * <p>Run: {@code java -cp "target/classes;$(cat cp.txt)" com.cctv.spike.SegmentCopySpike <url|file> <outDir> [segSec] [totalSec]}
 */
public final class SegmentCopySpike {

    public static void main(String[] args) throws Exception {
        String source = args.length > 0 ? args[0] : "testdata/test_walkthrough.mp4";
        Path outDir = Path.of(args.length > 1 ? args[1] : "recordings/spike0b");
        int segSec = args.length > 2 ? Integer.parseInt(args[2]) : 15;
        int totalSec = args.length > 3 ? Integer.parseInt(args[3]) : 60;
        Files.createDirectories(outDir);

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(source)) {
            grabber.setOption("rtsp_transport", "tcp");
            grabber.start();
            int videoStream = grabber.getVideoStream();
            System.out.printf("[0b] source %s %dx%d codec=%d fps=%.2f videoStream=%d%n",
                    source, grabber.getImageWidth(), grabber.getImageHeight(),
                    grabber.getVideoCodec(), grabber.getFrameRate(), videoStream);

            long startNanos = System.nanoTime();
            long segIndex = 0;
            long basePts = -1;
            int packetsInSeg = 0, totalPackets = 0, keyframes = 0, audioSkipped = 0;
            FFmpegFrameRecorder recorder = null;
            long segOpenedMs = 0;

            while (true) {
                AVPacket p = grabber.grabPacket();
                if (p == null) break;
                if (p.stream_index() != videoStream) { audioSkipped++; continue; }
                boolean key = isKeyframe(p);
                if (basePts < 0) basePts = p.pts();
                long elapsedMs = (long) ((System.nanoTime() - startNanos) / 1_000_000);
                if (elapsedMs >= totalSec * 1000L) break;

                boolean needCut = recorder == null
                        || (key && elapsedMs - segOpenedMs >= segSec * 1000L);
                if (needCut) {
                    if (recorder != null) {
                        System.out.printf("[0b] segment %d closed: packets=%d keyframes=%d size=%d B%n",
                                segIndex - 1, packetsInSeg, keyframes, sizeOf(outDir, segIndex - 1));
                        recorder.stop();
                        recorder.release();
                    }
                    File out = outDir.resolve(String.format("seg_%03d.mkv", segIndex)).toFile();
                    recorder = openCopyRecorder(out, grabber);
                    segIndex++;
                    packetsInSeg = 0;
                    keyframes = 0;
                    segOpenedMs = elapsedMs;
                    System.out.printf("[0b] segment %d opened at %d ms (keyframe=%s)%n",
                            segIndex - 1, elapsedMs, key);
                }

                // Re-base timestamps so every segment starts near zero.
                long pts = p.pts() - basePts;
                long dts = p.dts() - basePts;
                p.pts(pts);
                p.dts(dts);
                recorder.recordPacket(p);
                packetsInSeg++;
                totalPackets++;
                if (key) keyframes++;
            }

            if (recorder != null) {
                System.out.printf("[0b] segment %d closed: packets=%d keyframes=%d size=%d B%n",
                        segIndex - 1, packetsInSeg, keyframes, sizeOf(outDir, segIndex - 1));
                recorder.stop();
                recorder.release();
            }
            System.out.printf("[0b] done segments=%d videoPackets=%d audioSkipped=%d wall=%.1fs%n",
                    segIndex, totalPackets, audioSkipped, (System.nanoTime() - startNanos) / 1e9);
        }
    }

    private static FFmpegFrameRecorder openCopyRecorder(File out, FFmpegFrameGrabber g)
            throws Exception {
        FFmpegFrameRecorder r = new FFmpegFrameRecorder(out,
                g.getImageWidth(), g.getImageHeight(), 0);
        r.setFormat("matroska");
        r.setVideoCodec(g.getVideoCodec());
        r.setPixelFormat(g.getPixelFormat());
        r.setFrameRate(g.getFrameRate());
        r.setVideoBitrate(Math.max(g.getVideoBitrate(), 1_000_000));
        r.setVideoOption("copyts", "0");
        r.setVideoOption("movflags", "+faststart");
        r.start();
        return r;
    }

    @SuppressWarnings("deprecation")
    private static boolean isKeyframe(AVPacket p) {
        return (p.flags() & org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY) != 0;
    }

    private static long sizeOf(Path dir, long index) {
        try {
            return Files.size(dir.resolve(String.format("seg_%03d.mkv", index)));
        } catch (Exception e) {
            return -1;
        }
    }
}
