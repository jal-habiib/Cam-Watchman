package com.cctv.app.label;

import com.cctv.app.db.ClipLabel;
import com.cctv.app.db.RecordingRepository;
import com.cctv.contract.Detection;
import com.cctv.contract.EventBus;
import com.cctv.contract.SegmentStarted;
import com.cctv.contract.ClipFinalized;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 4a/4b: turns the engine's detections into a per-clip label summary.
 *
 * <p>The engine publishes {@link SegmentStarted} when a segment opens and a {@link ClipFinalized}
 * when it closes; every {@link Detection} that arrives in between belongs to the open segment of
 * that camera. This class accumulates per-class first/last/max-confidence/count and, on finalize,
 * hands the summary to the repository (one {@code clip_labels} row per class) and writes an optional
 * JSON sidecar next to the clip with an atomic rename.
 *
 * <p>Listeners are enqueue-free: the accumulation is a few map operations, no I/O. The repository
 * does the database work on its own writer thread, and the sidecar write happens on a small daemon
 * thread so a slow disk can never stall the engine that published the event.
 */
public final class ClipLabeler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClipLabeler.class);

    /** Segment length used only to sanity-bound a detection to a segment; the engine's own timing wins. */
    static final long ASSUME_SEGMENT_MS = 60_000;

    private record ClassStats(int count, long firstSeenUtcMs, long lastSeenUtcMs, double maxConfidence) {
        ClassStats add(Detection detection) {
            return new ClassStats(
                    count + 1,
                    Math.min(firstSeenUtcMs, detection.frameUtcMs()),
                    Math.max(lastSeenUtcMs, detection.frameUtcMs()),
                    Math.max(maxConfidence, detection.confidence()));
        }

        ClipLabel toLabel(String objectClass) {
            return new ClipLabel(objectClass, firstSeenUtcMs, lastSeenUtcMs, maxConfidence, count);
        }
    }

    private record OpenSegment(long startUtcMs, Map<String, ClassStats> classes) {
    }

    private final RecordingRepository repository;
    private final Map<Long, java.util.TreeMap<Long, OpenSegment>> open = new HashMap<>();
    private final EventBus.Subscription segmentSubscription;
    private final EventBus.Subscription detectionSubscription;
    private final EventBus.Subscription finalizedSubscription;
    private final SidecarWriter sidecar;

    public ClipLabeler(EventBus bus, RecordingRepository repository, boolean writeSidecars) {
        this.repository = repository;
        this.sidecar = writeSidecars ? new SidecarWriter() : null;
        this.segmentSubscription = bus.subscribe(SegmentStarted.class, this::onSegmentStarted);
        this.detectionSubscription = bus.subscribe(Detection.class, this::onDetection);
        this.finalizedSubscription = bus.subscribe(ClipFinalized.class, this::onFinalized);
        log.info("clip labeler ready (sidecars: {})", writeSidecars ? "on" : "off");
    }

    private void onSegmentStarted(SegmentStarted event) {
        synchronized (open) {
            java.util.TreeMap<Long, OpenSegment> segments =
                    open.computeIfAbsent(event.cameraId(), id -> new java.util.TreeMap<>());
            if (segments.containsKey(event.startUtcMs())) {
                return;
            }
            segments.put(event.startUtcMs(), new OpenSegment(event.startUtcMs(), new HashMap<>()));
            // The recorder announces the next segment before finalizing the previous one, so a
            // small window of open segments is kept; older ones are already finalized.
            while (segments.size() > 6) {
                segments.pollFirstEntry();
            }
        }
    }

    private void onDetection(Detection detection) {
        synchronized (open) {
            java.util.TreeMap<Long, OpenSegment> segments = open.get(detection.cameraId());
            if (segments == null || segments.isEmpty()) {
                return;
            }
            OpenSegment segment = segments.lastEntry().getValue();
            long offset = detection.frameUtcMs() - segment.startUtcMs();
            if (offset < 0 || offset > ASSUME_SEGMENT_MS) {
                return;
            }
            segment.classes().merge(detection.objectClass(),
                    new ClassStats(1, detection.frameUtcMs(), detection.frameUtcMs(),
                            detection.confidence()),
                    (existing, ignored) -> existing.add(detection));
        }
    }

    private void onFinalized(ClipFinalized event) {
        List<ClipLabel> labels;
        synchronized (open) {
            java.util.TreeMap<Long, OpenSegment> segments = open.get(event.cameraId());
            OpenSegment segment = segments == null ? null : segments.remove(event.startUtcMs());
            labels = segment == null ? List.of() : labelsOf(segment);
        }
        if (!labels.isEmpty()) {
            repository.clipLabels(event.cameraId(), event.startUtcMs(), labels);
        }
        if (sidecar != null) {
            sidecar.write(event, labels);
        }
        log.debug("clip finalized: camera {} start {} labels {}", event.cameraId(),
                event.startUtcMs(), labels.size());
    }

    private static List<ClipLabel> labelsOf(OpenSegment segment) {
        return segment.classes().entrySet().stream()
                .map(e -> e.getValue().toLabel(e.getKey()))
                .sorted((a, b) -> a.objectClass().compareTo(b.objectClass()))
                .toList();
    }

    /** Visible for tests: labels currently accumulated for a camera, if a segment is open. */
    Optional<List<ClipLabel>> pending(long cameraId) {
        synchronized (open) {
            java.util.TreeMap<Long, OpenSegment> segments = open.get(cameraId);
            if (segments == null || segments.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(labelsOf(segments.lastEntry().getValue()));
        }
    }

    @Override
    public void close() {
        segmentSubscription.close();
        detectionSubscription.close();
        finalizedSubscription.close();
        if (sidecar != null) {
            sidecar.close();
        }
    }
}
