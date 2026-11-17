package com.cctv.app.alert;

import com.cctv.app.db.RecordingRepository;
import com.cctv.contract.EventBus;
import com.cctv.contract.IntrusionEvent;
import com.cctv.contract.ZoneAlert;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 5d, persistence half: turns an {@link IntrusionEvent} into a snapshot PNG, an {@code alerts}
 * row and a {@link ZoneAlert} for the UI.
 *
 * <p>Contract rule: the bus listener only enqueues. PNG encoding, file writes and the database go
 * through one daemon thread, so a slow disk can never stall the engine's publisher. The UI half is
 * a callback ({@link #onPresented(Consumer)}) that the app marshals onto the JavaFX thread.
 */
public final class AlertService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** What the UI needs: the persisted alert plus the zone name that fired it. */
    public record PresentedAlert(ZoneAlert alert, String zoneName) {
    }

    private final Path snapshotsDir;
    private final RecordingRepository repository;
    private final EventBus bus;
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "alert-writer");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicInteger presented = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private volatile Consumer<PresentedAlert> presentedSink = alert -> { };
    private final LongUnaryOperator segmentStartForCamera;
    private final long cooldownMs = Long.getLong("cctv.alert.cooldown.ms", 60_000L);
    private final java.util.Map<Long, Long> lastAlertByZone =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger suppressed = new AtomicInteger();
    private EventBus.Subscription subscription;

    public AlertService(Path snapshotsDir, RecordingRepository repository, EventBus bus) {
        this(snapshotsDir, repository, bus, cameraId -> 0L);
    }

    /**
     * @param segmentStartForCamera fallback used when an event cannot name its recording (the
     *     engine currently sends 0); the app resolves the segment being written for that camera so
     *     the alert row still links to the clip it happened in
     */
    public AlertService(Path snapshotsDir, RecordingRepository repository, EventBus bus,
                        LongUnaryOperator segmentStartForCamera) {
        this.snapshotsDir = snapshotsDir;
        this.repository = repository;
        this.bus = bus;
        this.segmentStartForCamera =
                segmentStartForCamera == null ? cameraId -> 0L : segmentStartForCamera;
        try {
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            log.warn("could not create the snapshot directory {}: {}", snapshotsDir, e.toString());
        }
        this.subscription = bus.subscribe(IntrusionEvent.class, this::enqueue);
        log.info("alert presenter ready (snapshots in {})", snapshotsDir);
    }

    /** Registers the UI callback. Called on the alert-writer thread; the app hops to FX there. */
    public void onPresented(Consumer<PresentedAlert> sink) {
        this.presentedSink = sink == null ? alert -> { } : sink;
    }

    /** Bus listener: enqueue only, never do work here. */
    private void enqueue(IntrusionEvent event) {
        writer.submit(() -> {
            if (!allowCooldown(event)) {
                return;
            }
            persist(event);
        });
    }

    /**
     * Presentation policy, not detection: the engine fires one event per confirmed track entry, so
     * two people stepping in together would otherwise produce two popups and two rows in the same
     * second. The plan asks for one alert per zone per 60 s; the engine owns its own re-arm, this is
     * the notification-level safety net and can be tuned with {@code -Dcctv.alert.cooldown.ms}.
     */
    private boolean allowCooldown(IntrusionEvent event) {
        long now = System.currentTimeMillis();
        Long last = lastAlertByZone.get(event.zone().id());
        if (last != null && now - last < cooldownMs) {
            suppressed.incrementAndGet();
            log.info("alert suppressed: camera {} zone {} ({}) within {} ms cooldown", event.cameraId(),
                    event.zone().id(), event.zone().name(), cooldownMs);
            return false;
        }
        lastAlertByZone.put(event.zone().id(), now);
        return true;
    }

    private void persist(IntrusionEvent event) {
        String alertId = UUID.randomUUID().toString();
        Path snapshot = snapshotsDir.resolve("alert-" + alertId + ".png");
        long createdUtcMs = System.currentTimeMillis();
        try {
            BufferedImage image = event.snapshotCopy();
            if (!ImageIO.write(image, "png", snapshot.toFile())) {
                log.warn("no PNG writer available for the alert snapshot");
            }
        } catch (IOException | RuntimeException e) {
            failed.incrementAndGet();
            log.warn("could not write the alert snapshot {}: {}", snapshot, e.toString());
            return;
        }
        long recordingStartUtcMs = event.recordingStartUtcMs() > 0L
                ? event.recordingStartUtcMs()
                : segmentStartForCamera.applyAsLong(event.cameraId());
        try {
            repository.alert(event.cameraId(), event.zone().id(), recordingStartUtcMs,
                    snapshot, createdUtcMs);
            ZoneAlert alert = new ZoneAlert(alertId, event.cameraId(), event.zone().id(),
                    recordingStartUtcMs, snapshot, createdUtcMs);
            presented.incrementAndGet();
            presentedSink.accept(new PresentedAlert(alert, event.zone().name()));
            bus.publish(alert);
            log.info("alert {}: camera {} zone {} ({}) snapshot {}", alertId, event.cameraId(),
                    event.zone().id(), event.zone().name(), snapshot.getFileName());
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            log.warn("could not persist the alert for camera {}: {}", event.cameraId(), e.toString());
        }
    }

    public int presentedCount() {
        return presented.get();
    }

    public int failedCount() {
        return failed.get();
    }

    /** Alerts dropped by the notification cooldown (another track entered the same zone). */
    public int suppressedCount() {
        return suppressed.get();
    }

    @Override
    public void close() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        log.info("alert presenter closed ({} presented, {} failed)", presented.get(), failed.get());
    }
}
