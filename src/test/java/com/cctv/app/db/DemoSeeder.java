package com.cctv.app.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Development-only seeder for the 4c search UI: writes a handful of "kept" clip rows and labels
 * into a throwaway data directory so the search screen can be exercised before the engine exists.
 * Not used by the app; run it through {@code scripts/seed-demo-clips.ps1}.
 */
public final class DemoSeeder {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: DemoSeeder <dbFile> <recordingsDir> <sourceVideo>");
            System.exit(2);
        }
        Path dbFile = Path.of(args[0]);
        Path recordings = Path.of(args[1]);
        Path source = Path.of(args[2]);
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("source video not found: " + source);
        }
        Files.createDirectories(recordings);
        try (Connection connection = Database.open(dbFile)) {
            connection.createStatement().executeUpdate("DELETE FROM clip_labels");
            connection.createStatement().executeUpdate("DELETE FROM recordings");
            long now = System.currentTimeMillis();
            String[] objects = {"car,person", "person", "car", "truck", "person,car", "car"};
            int[] counts = {7, 2, 3, 1, 4, 9};
            double[] confidences = {0.93, 0.81, 0.88, 0.72, 0.90, 0.96};
            for (int i = 0; i < objects.length; i++) {
                long start = Instant.ofEpochMilli(now).minus(i * 7L, ChronoUnit.HOURS).toEpochMilli();
                long end = start + 15_000;
                Path file = recordings.resolve("clip_" + (i + 1) + ".mp4");
                Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING);
                long id = insert(connection, i + 1, file, start, end);
                for (String objectClass : objects[i].split(",")) {
                    label(connection, id, objectClass, counts[i], confidences[i], start);
                }
                System.out.printf("seeded clip %d %s (%s)%n", id, file.getFileName(), objects[i]);
            }
        }
    }

    private static long insert(Connection connection, long cameraId, Path file, long start, long end)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO recordings (camera_id, file_path, start_utc, end_utc, size_bytes, trigger_type, status)
                VALUES (?, ?, ?, ?, ?, 'detection', 'ready')
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, cameraId);
            ps.setString(2, file.toString());
            ps.setLong(3, start);
            ps.setLong(4, end);
            ps.setLong(5, Files.size(file));
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void label(Connection connection, long recordingId, String objectClass, int count,
                              double confidence, long start) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO clip_labels (recording_id, object_class, first_seen, last_seen, max_confidence, count)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, recordingId);
            ps.setString(2, objectClass);
            ps.setLong(3, start);
            ps.setLong(4, start + 9_000);
            ps.setDouble(5, confidence);
            ps.setInt(6, count);
            ps.executeUpdate();
        }
    }
}
