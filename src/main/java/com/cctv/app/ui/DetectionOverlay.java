package com.cctv.app.ui;

import com.cctv.contract.Detection;
import com.cctv.engine.TrackedObject;
import com.cctv.engine.TrackingUpdate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Draws the newest detection per object class over the Camera 1 video (plan 2d/6a), and — once the
 * engine publishes {@link TrackingUpdate} (plan 7a) — the tracked boxes with their stable id, e.g.
 * {@code person #12}.
 *
 * <p>Detections are normalized; the canvas maps them onto its own size, so the overlay stays
 * correct at any cell size. Entries fade out after {@link #LINGER_MS} without a fresh detection.
 * Coordinates are clamped, so a box can never be drawn outside the video area.
 */
public final class DetectionOverlay extends Canvas {

    private static final long LINGER_MS = 2_000;
    private static final long TRACK_LINGER_MS = 1_500;
    private static final Color STROKE = Color.web("#ff6a4d");
    private static final Color FILL = Color.web("#ff6a4d", 0.12);
    private static final Color TRACK_STROKE = Color.web("#5ad1ff");
    private static final Color TRACK_FILL = Color.web("#5ad1ff", 0.10);
    private static final Color ZONE_STROKE = Color.web("#ffb454");
    private static final Color LINE_STROKE = Color.web("#7ee787");

    private record Shown(Detection detection, long atMs) {
    }

    private final Map<String, Shown> latest = new ConcurrentHashMap<>();
    private final Map<Long, TrackedObject> tracks = new ConcurrentHashMap<>();
    private volatile java.util.List<com.cctv.contract.ZoneDefinition> zones = java.util.List.of();
    private volatile java.util.List<com.cctv.contract.CountLine> lines = java.util.List.of();
    private AnimationTimer timer;
    private boolean started;

    public DetectionOverlay() {
        setMouseTransparent(true);
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        timer = new AnimationTimer() {
            private long last;
            private long drawnAt;

            @Override
            public void handle(long now) {
                if (now - last < 80_000_000L) {
                    return;
                }
                last = now;
                if (now - drawnAt < 33_000_000L) {
                    return;
                }
                drawnAt = now;
                redraw();
            }
        };
        timer.start();
    }

    public void accept(Detection detection) {
        latest.compute(detection.objectClass(), (key, existing) ->
                existing == null || detection.frameUtcMs() >= existing.detection().frameUtcMs()
                        ? new Shown(detection, System.currentTimeMillis())
                        : existing);
    }

    /**
     * Newest tracking snapshot (plan 7a). Call on the JavaFX thread; the app hops from the engine
     * callback with {@code Platform.runLater}. Tracks replace the per-class boxes while present, so
     * one object is never drawn twice.
     */
    public void acceptTracking(TrackingUpdate update) {
        for (TrackedObject track : update.tracks()) {
            tracks.put(track.trackId(), track);
        }
    }

    /** Active zones to outline on the live view (plan 5c: zones persist and redraw). */
    public void setZones(java.util.List<com.cctv.contract.ZoneDefinition> zones) {
        this.zones = zones == null ? java.util.List.of() : java.util.List.copyOf(zones);
    }

    /** Active counting lines to outline on the live view (plan 7b). */
    public void setLines(java.util.List<com.cctv.contract.CountLine> lines) {
        this.lines = lines == null ? java.util.List.of() : java.util.List.copyOf(lines);
    }

    private void redraw() {
        GraphicsContext g = getGraphicsContext2D();
        double width = getWidth();
        double height = getHeight();
        g.clearRect(0, 0, width, height);
        if (width <= 0 || height <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        latest.entrySet().removeIf(e -> now - e.getValue().atMs() > LINGER_MS);
        tracks.values().removeIf(track -> now - track.lastSeenUtcMs() > TRACK_LINGER_MS);
        g.setFont(Font.font("Segoe UI", 12));
        drawZones(g, width, height);
        drawLines(g, width, height);
        if (!tracks.isEmpty()) {
            for (TrackedObject track : tracks.values()) {
                boolean confirmed = track.confirmed();
                double alpha = track.missedFrames() > 0 ? 0.35 : (confirmed ? 1.0 : 0.6);
                drawBox(g, track.x1(), track.y1(), track.x2(), track.y2(), width, height,
                        TRACK_STROKE, TRACK_FILL, alpha, !confirmed,
                        track.objectClass() + " #" + track.trackId());
            }
            return;
        }
        for (Shown shown : latest.values()) {
            Detection d = shown.detection();
            drawBox(g, d.x1(), d.y1(), d.x2(), d.y2(), width, height, STROKE, FILL, 1.0, false,
                    String.format(java.util.Locale.ROOT, "%s %.2f", d.objectClass(), d.confidence()));
        }
    }

    private void drawZones(GraphicsContext g, double width, double height) {
        for (com.cctv.contract.ZoneDefinition zone : zones) {
            if (!zone.active()) {
                continue;
            }
            double x = clamp(zone.x()) * width;
            double y = clamp(zone.y()) * height;
            double w = Math.max(1, Math.min(1, zone.x() + zone.w()) * width - x);
            double h = Math.max(1, Math.min(1, zone.y() + zone.h()) * height - y);
            g.setGlobalAlpha(0.95);
            g.setStroke(ZONE_STROKE);
            g.setLineWidth(2);
            g.setLineDashes(8, 5);
            g.strokeRect(x, y, w, h);
            g.setLineDashes();
            g.setFill(Color.rgb(0, 0, 0, 0.55));
            g.fillRect(x, y - 14, Math.max(60, zone.name().length() * 7.0), 16);
            g.setFill(ZONE_STROKE);
            g.fillText(zone.name(), x + 3, y - 2);
            g.setGlobalAlpha(1.0);
        }
    }

    private void drawLines(GraphicsContext g, double width, double height) {
        for (com.cctv.contract.CountLine line : lines) {
            if (!line.active()) {
                continue;
            }
            double x1 = clamp(line.x1()) * width;
            double y1 = clamp(line.y1()) * height;
            double x2 = clamp(line.x2()) * width;
            double y2 = clamp(line.y2()) * height;
            g.setGlobalAlpha(0.95);
            g.setStroke(LINE_STROKE);
            g.setLineWidth(2.5);
            g.strokeLine(x1, y1, x2, y2);
            double mx = (x1 + x2) / 2;
            double my = (y1 + y2) / 2;
            g.setFill(Color.rgb(0, 0, 0, 0.55));
            g.fillRect(mx - 4, my - 14, Math.max(60, line.name().length() * 7.0), 16);
            g.setFill(LINE_STROKE);
            g.fillText(line.name(), mx + 1, my - 2);
            g.setGlobalAlpha(1.0);
        }
    }

    private static void drawBox(GraphicsContext g, double nX1, double nY1, double nX2, double nY2,
                                double width, double height, Color stroke, Color fill, double alpha,
                                boolean dashed, String label) {
        double x1 = clamp(nX1) * width;
        double y1 = clamp(nY1) * height;
        double x2 = clamp(nX2) * width;
        double y2 = clamp(nY2) * height;
        double w = Math.max(1, x2 - x1);
        double h = Math.max(1, y2 - y1);
        g.setGlobalAlpha(alpha);
        g.setFill(fill);
        g.fillRect(x1, y1, w, h);
        g.setStroke(stroke);
        g.setLineWidth(2);
        if (dashed) {
            g.setLineDashes(6, 4);
        } else {
            g.setLineDashes();
        }
        g.strokeRect(x1, y1, w, h);
        g.setLineDashes();
        double textY = y1 > 16 ? y1 - 5 : y1 + 14;
        g.setFill(Color.rgb(0, 0, 0, 0.55));
        g.fillRect(x1, textY - 12, Math.max(60, label.length() * 7.0), 16);
        g.setFill(stroke);
        g.fillText(label, x1 + 3, textY);
        g.setGlobalAlpha(1.0);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    public void close() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        latest.clear();
    }
}
