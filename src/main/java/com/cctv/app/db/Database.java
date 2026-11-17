package com.cctv.app.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Opens the SQLite database and applies the schema from the build plan (p.3–4).
 *
 * <p>Every connection runs in WAL mode with a busy timeout so the single writer thread and the
 * read-only UI queries do not block each other for long.
 */
public final class Database {

    public static final int SCHEMA_VERSION = 3;

    /** Plan §Data and storage rules: one row per kept/kept-candidate segment, plus the side tables. */
    private static final String[] DDL = {
            """
            CREATE TABLE IF NOT EXISTS cameras (
              id          INTEGER PRIMARY KEY,
              name        TEXT    NOT NULL,
              url         TEXT,
              substream_url TEXT,
              record      INTEGER NOT NULL DEFAULT 0,
              ai          INTEGER NOT NULL DEFAULT 0,
              last_seen   INTEGER,
              enabled     INTEGER NOT NULL DEFAULT 1,
              deleted     INTEGER NOT NULL DEFAULT 0
            )""",
            """
            CREATE TABLE IF NOT EXISTS recordings (
              id           INTEGER PRIMARY KEY,
              camera_id    INTEGER NOT NULL,
              file_path    TEXT    NOT NULL,
              start_utc    INTEGER NOT NULL,
              end_utc      INTEGER,
              size_bytes   INTEGER NOT NULL DEFAULT 0,
              trigger_type TEXT    NOT NULL DEFAULT 'none',
              status       TEXT    NOT NULL DEFAULT 'writing'
                           CHECK (status IN ('writing','ready','deleting')),
              UNIQUE (camera_id, start_utc)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_recordings_start ON recordings(start_utc)",
            "CREATE INDEX IF NOT EXISTS idx_recordings_status ON recordings(status)",
            """
            CREATE TABLE IF NOT EXISTS clip_labels (
              recording_id   INTEGER NOT NULL,
              object_class   TEXT    NOT NULL,
              first_seen     INTEGER NOT NULL,
              last_seen      INTEGER NOT NULL,
              max_confidence REAL    NOT NULL,
              count          INTEGER NOT NULL,
              PRIMARY KEY (recording_id, object_class)
            )""",
            """
            CREATE TABLE IF NOT EXISTS zones (
              id        INTEGER PRIMARY KEY,
              camera_id INTEGER NOT NULL,
              name      TEXT    NOT NULL,
              x         REAL    NOT NULL,
              y         REAL    NOT NULL,
              w         REAL    NOT NULL,
              h         REAL    NOT NULL,
              active    INTEGER NOT NULL DEFAULT 1
            )""",
            """
            CREATE TABLE IF NOT EXISTS alerts (
              id            INTEGER PRIMARY KEY,
              camera_id     INTEGER NOT NULL,
              zone_id       INTEGER NOT NULL,
              recording_id  INTEGER,
              snapshot_path TEXT,
              created_utc   INTEGER NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS count_events (
              id         INTEGER PRIMARY KEY,
              camera_id  INTEGER NOT NULL,
              line_id    INTEGER NOT NULL DEFAULT 0,
              track_id   INTEGER NOT NULL,
              direction  TEXT    NOT NULL,
              crossed_utc INTEGER NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS idx_count_events_day ON count_events(crossed_utc)",
            """
            CREATE TABLE IF NOT EXISTS count_lines (
              id        INTEGER PRIMARY KEY,
              camera_id INTEGER NOT NULL,
              name      TEXT    NOT NULL,
              x1        REAL    NOT NULL,
              y1        REAL    NOT NULL,
              x2        REAL    NOT NULL,
              y2        REAL    NOT NULL,
              active    INTEGER NOT NULL DEFAULT 1
            )""",
            """
            CREATE TABLE IF NOT EXISTS count_summaries (
              date      TEXT    NOT NULL,
              camera_id INTEGER NOT NULL,
              count_in  INTEGER NOT NULL DEFAULT 0,
              count_out INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (date, camera_id)
            )""",
    };

    private Database() {
    }

    /**
     * Forward-only migrations for databases created by an older build. {@code CREATE TABLE IF NOT
     * EXISTS} never adds a column to an existing table, so each version adds its columns
     * explicitly: v2 adds {@code line_id} to count_events, v3 adds the camera
     * {@code enabled}/{@code deleted} flags that make the camera list editable at runtime while
     * preserving recordings, alerts and counts of removed cameras.
     */
    private static void migrate(Statement s) throws SQLException {
        if (!columnExists(s, "count_events", "line_id")) {
            s.execute("ALTER TABLE count_events ADD COLUMN line_id INTEGER NOT NULL DEFAULT 0");
        }
        if (!columnExists(s, "cameras", "enabled")) {
            s.execute("ALTER TABLE cameras ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1");
        }
        if (!columnExists(s, "cameras", "deleted")) {
            s.execute("ALTER TABLE cameras ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
        }
    }

    private static boolean columnExists(Statement s, String table, String column)
            throws SQLException {
        try (java.sql.ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Connection open(Path dbFile) throws SQLException {
        Path parent = dbFile.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (java.io.IOException e) {
            throw new SQLException("cannot create database folder " + parent, e);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            try {
                s.execute("PRAGMA journal_mode=WAL");
            } catch (SQLException e) {
                // A second connection may be switching the mode at the same moment; the mode is a
                // property of the file, so a later connection still sees WAL.
            }
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
            for (String ddl : DDL) {
                s.execute(ddl);
            }
            migrate(s);
            s.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
