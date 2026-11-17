package com.cctv.app.label;

import com.cctv.app.db.ClipLabel;
import com.cctv.contract.ClipFinalized;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 4b: writes a small JSON sidecar next to a finished clip, after the clip is finalized.
 *
 * <p>The contract rule is "no I/O in a bus listener", so this class owns one daemon thread and a
 * bounded queue; {@link #write(ClipFinalized, List)} only enqueues. The sidecar is written to a
 * temporary sibling and moved into place atomically, so a reader never sees a half-written file.
 * The sidecar is an export, not the source of truth: the database row is authoritative.
 */
final class SidecarWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SidecarWriter.class);
    private static final int CAPACITY = 256;

    private record Job(ClipFinalized clip, List<ClipLabel> labels) {
    }

    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    SidecarWriter() {
        worker = new Thread(this::loop, "sidecar-writer");
        worker.setDaemon(true);
        worker.start();
    }

    void write(ClipFinalized clip, List<ClipLabel> labels) {
        if (!queue.offer(new Job(clip, List.copyOf(labels)))) {
            failed.incrementAndGet();
            log.warn("sidecar queue full; skipped {}", clip.file());
        }
    }

    long writtenCount() {
        return written.get();
    }

    long failedCount() {
        return failed.get();
    }

    private void loop() {
        while (running) {
            Job job;
            try {
                job = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (job == null) {
                continue;
            }
            try {
                writeFile(job.clip(), job.labels());
                written.incrementAndGet();
            } catch (IOException e) {
                failed.incrementAndGet();
                log.warn("sidecar failed for {}: {}", job.clip().file(), e.toString());
            }
        }
    }

    private static void writeFile(ClipFinalized clip, List<ClipLabel> labels) throws IOException {
        Path file = clip.file();
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null) {
            throw new IOException("clip has no directory: " + file);
        }
        Path target = dir.resolve(file.getFileName() + ".json");
        Path temp = dir.resolve(file.getFileName() + ".json.tmp");
        long durationMs = Math.max(0, clip.endUtcMs() - clip.startUtcMs());
        StringBuilder json = new StringBuilder(256);
        json.append("{\n");
        json.append("  \"schemaVersion\": 1,\n");
        json.append("  \"cameraId\": ").append(clip.cameraId()).append(",\n");
        json.append("  \"startUtcMs\": ").append(clip.startUtcMs()).append(",\n");
        json.append("  \"endUtcMs\": ").append(clip.endUtcMs()).append(",\n");
        json.append("  \"durationMs\": ").append(durationMs).append(",\n");
        json.append("  \"sizeBytes\": ").append(clip.sizeBytes()).append(",\n");
        json.append("  \"kept\": ").append(clip.keep()).append(",\n");
        json.append("  \"labels\": [");
        for (int i = 0; i < labels.size(); i++) {
            ClipLabel label = labels.get(i);
            json.append(i == 0 ? "\n" : ",\n");
            json.append("    {\"objectClass\": ").append(quote(label.objectClass()))
                    .append(", \"count\": ").append(label.count())
                    .append(", \"maxConfidence\": ")
                    .append(String.format(Locale.ROOT, "%.3f", label.maxConfidence()))
                    .append(", \"firstSeenUtcMs\": ").append(label.firstSeenUtcMs())
                    .append(", \"lastSeenUtcMs\": ").append(label.lastSeenUtcMs())
                    .append('}');
        }
        if (!labels.isEmpty()) {
            json.append('\n').append("  ");
        }
        json.append("]\n}\n");
        Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
