package com.cctv.app.net;

import com.cctv.app.config.Redaction;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;

/**
 * One-shot RTSP connection test for the camera form (plan 1b, camera management requirement).
 *
 * <p>It opens a grabber, waits for the first video frame and closes it. It must only be called for
 * a stream that is not already connected by the wall/engine, because the locked architecture allows
 * one connection per stream profile; the form disables the button for a running camera.
 */
public final class RtspProbe {

    /** Probe result with a user-facing, credential-free message. */
    public record Result(boolean ok, String message, int width, int height) {

        public static Result failure(String message) {
            return new Result(false, message, 0, 0);
        }
    }

    private RtspProbe() {
    }

    /**
     * Connects to {@code url} with a TCP RTSP transport, grabs one frame and disconnects.
     *
     * @param url     camera URL (may carry credentials; never echoed raw)
     * @param timeout overall budget for connect + first frame
     */
    public static Result probe(URI url, Duration timeout) {
        if (url == null) {
            return Result.failure("no URL to test");
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rtsp-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Result> future = executor.submit(() -> connect(url));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Result.failure("timed out after " + timeout.toSeconds()
                    + " s - check the URL, credentials and network");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure("probe interrupted");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Result.failure(describe(cause));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Result connect(URI url) {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url.toString());
        try {
            grabber.setOption("rtsp_transport", "tcp");
            grabber.setOption("stimeout", "5000000");
            grabber.setOption("analyzeduration", "5000000");
            grabber.start();
            Frame frame = grabber.grabImage();
            if (frame == null) {
                return Result.failure("connected but no video frame arrived");
            }
            int width = frame.imageWidth > 0 ? frame.imageWidth : grabber.getImageWidth();
            int height = frame.imageHeight > 0 ? frame.imageHeight : grabber.getImageHeight();
            if (width <= 0 || height <= 0) {
                return Result.failure("connected but the stream reports no video size");
            }
            String codec = grabber.getVideoCodecName();
            String type = codec == null || codec.isBlank() ? "video" : codec;
            return new Result(true, "connected - " + width + "x" + height + " " + type, width, height);
        } catch (Exception e) {
            return Result.failure(describe(e));
        } finally {
            try {
                grabber.stop();
            } catch (Exception ignored) {
                // best effort
            }
            try {
                grabber.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
        String redacted = Redaction.redact(message);
        if (redacted.length() > 220) {
            redacted = redacted.substring(0, 220) + "...";
        }
        return "connection failed - " + redacted;
    }
}
