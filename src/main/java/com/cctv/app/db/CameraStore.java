package com.cctv.app.db;

import com.cctv.app.config.CameraConfig;
import com.cctv.app.config.Redaction;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Runtime camera list (plan 1b plus the "Manage cameras" requirement).
 *
 * <p>SQLite is the source of truth once the application has started: cameras can be added,
 * edited, disabled and removed from the UI without touching {@code cameras.properties}. The
 * properties file is only imported on first run, when the table is still empty.
 *
 * <p>Removal is a soft delete ({@code deleted = 1}) so recordings, labels, alerts and counts that
 * reference the camera id keep their history. The wall and the engine only see rows with
 * {@code deleted = 0}; disabled cameras stay listed but are not started.
 */
public final class CameraStore {

    /** The wall is a 2x2 grid and the build plan caps the product at four cameras. */
    public static final int MAX_CAMERAS = 4;

    /** One camera row as stored. URLs may carry RTSP credentials; never log them raw. */
    public record Camera(long id, String name, URI url, URI substreamUrl, boolean record,
            boolean ai, boolean enabled) {

        public Camera {
            Objects.requireNonNull(name, "name");
            if (id <= 0) {
                throw new IllegalArgumentException("camera id must be positive: " + id);
            }
        }

        public boolean hasMain() {
            return url != null;
        }

        public boolean hasSubstream() {
            return substreamUrl != null;
        }

        public boolean needsMainStream() {
            return ai || record || !hasSubstream();
        }

        /** URL to show in the UI: main when present, else substream, credentials redacted. */
        public String displayUrl() {
            URI shown = hasMain() ? url : substreamUrl;
            return shown == null ? "" : Redaction.redact(shown.toString());
        }

        public CameraConfig toConfig() {
            return new CameraConfig(id, name, url, substreamUrl, record, ai);
        }

        public Draft toDraft() {
            return new Draft(name, url, substreamUrl, record, ai, enabled);
        }
    }

    /** An editable camera entry (no id yet, or the edited values of an existing one). */
    public record Draft(String name, URI url, URI substreamUrl, boolean record, boolean ai,
            boolean enabled) {

        public Draft {
            Objects.requireNonNull(name, "name");
        }

        public static Draft of(Camera camera) {
            return new Draft(camera.name(), camera.url(), camera.substreamUrl(),
                    camera.record(), camera.ai(), camera.enabled());
        }
    }

    private final Path dbFile;

    public CameraStore(Path dbFile) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile");
    }

    /** Cameras that are part of the wall (not removed), including disabled ones. */
    public List<Camera> all() {
        return query("WHERE deleted = 0 ORDER BY id", null);
    }

    /** Cameras the application should start (not removed and enabled). */
    public List<Camera> enabled() {
        return query("WHERE deleted = 0 AND enabled = 1 ORDER BY id", null);
    }

    public Camera byId(long id) {
        List<Camera> rows = query("WHERE id = ? AND deleted = 0", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Cameras that are not soft-deleted (enabled or disabled). */
    public int activeCount() {
        try (Connection connection = Database.open(dbFile);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM cameras WHERE deleted = 0")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot count cameras: " + e.getMessage(), e);
        }
    }

    /** Adds a camera and returns its id. Rejects blank names, bad URLs and duplicates. */    public long add(Draft draft) {
        Validated validated = validate(draft, -1);
        if (activeCount() >= MAX_CAMERAS) {
            throw new IllegalArgumentException("at most " + MAX_CAMERAS + " cameras are supported");
        }
        long id = nextId();
        try (Connection connection = Database.open(dbFile)) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO cameras(id, name, url, substream_url, record, ai, enabled, deleted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 0)")) {
                ps.setLong(1, id);
                ps.setString(2, validated.name());
                ps.setString(3, validated.url());
                ps.setString(4, validated.substreamUrl());
                ps.setInt(5, validated.record() ? 1 : 0);
                ps.setInt(6, validated.ai() ? 1 : 0);
                ps.setInt(7, validated.enabled() ? 1 : 0);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot add camera: " + e.getMessage(), e);
        }
        return id;
    }

    /** Replaces the editable values of an existing camera. */
    public void update(long id, Draft draft) {
        if (id <= 0) {
            throw new IllegalArgumentException("camera id must be positive: " + id);
        }
        Validated validated = validate(draft, id);
        try (Connection connection = Database.open(dbFile);
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE cameras SET name = ?, url = ?, substream_url = ?, record = ?,"
                                + " ai = ?, enabled = ? WHERE id = ? AND deleted = 0")) {
            ps.setString(1, validated.name());
            ps.setString(2, validated.url());
            ps.setString(3, validated.substreamUrl());
            ps.setInt(4, validated.record() ? 1 : 0);
            ps.setInt(5, validated.ai() ? 1 : 0);
            ps.setInt(6, validated.enabled() ? 1 : 0);
            ps.setLong(7, id);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("camera " + id + " does not exist");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot update camera: " + e.getMessage(), e);
        }
    }

    public void setEnabled(long id, boolean enabled) {
        try (Connection connection = Database.open(dbFile);
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE cameras SET enabled = ? WHERE id = ? AND deleted = 0")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot change camera state: " + e.getMessage(), e);
        }
    }

    /**
     * Soft-deletes a camera: it disappears from the wall and the engine, while recordings, labels,
     * alerts and counts keep their rows (history preservation, owner requirement).
     */
    public void remove(long id) {
        try (Connection connection = Database.open(dbFile);
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE cameras SET deleted = 1, enabled = 0 WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot remove camera: " + e.getMessage(), e);
        }
    }

    /**
     * Imports {@code cameras.properties} entries when the table is still empty (first run or first
     * upgrade). Existing camera rows are never touched. Returns how many rows were inserted.
     */
    public int importIfEmpty(List<CameraConfig> configured) {
        if (!all().isEmpty()) {
            return 0;
        }
        int imported = 0;
        for (CameraConfig config : configured) {
            Draft draft = new Draft(config.name(), config.mainUrl(), config.substreamUrl(),
                    config.record(), config.ai(), true);
            try {
                add(draft);
                imported++;
            } catch (IllegalArgumentException e) {
                // A duplicate or invalid row from the file is skipped; the rest still import.
            }
        }
        return imported;
    }

    private long nextId() {
        try (Connection connection = Database.open(dbFile);
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT COALESCE(MAX(id), 0) + 1 FROM cameras");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 1L;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot allocate camera id: " + e.getMessage(), e);
        }
    }

    private List<Camera> query(String where, Long id) {
        List<Camera> cameras = new ArrayList<>();
        String sql = "SELECT id, name, url, substream_url, record, ai, enabled FROM cameras " + where;
        try (Connection connection = Database.open(dbFile);
                PreparedStatement ps = connection.prepareStatement(sql)) {
            if (id != null) {
                ps.setLong(1, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cameras.add(new Camera(rs.getLong(1), rs.getString(2), uri(rs.getString(3)),
                            uri(rs.getString(4)), rs.getInt(5) != 0, rs.getInt(6) != 0,
                            rs.getInt(7) != 0));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read cameras: " + e.getMessage(), e);
        }
        return cameras;
    }

    private record Validated(String name, String url, String substreamUrl, boolean record,
            boolean ai, boolean enabled) {
    }

    private Validated validate(Draft draft, long editingId) {
        String name = draft.name() == null ? "" : draft.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("camera name is required");
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("camera name must be at most 64 characters");
        }
        String url = validateUrl(draft.url(), "main stream URL");
        String substreamUrl = validateUrl(draft.substreamUrl(), "substream URL");
        if (url == null && substreamUrl == null) {
            throw new IllegalArgumentException("a camera needs at least one RTSP URL");
        }
        if ((draft.record() || draft.ai()) && url == null) {
            throw new IllegalArgumentException("recording and AI need a main stream URL");
        }
        List<Camera> existing = all();
        for (Camera camera : existing) {
            if (camera.id() == editingId) {
                continue;
            }
            if (camera.name().trim().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("another camera already uses the name '" + name + "'");
            }
            if (sameUri(camera.url(), url) || sameUri(camera.substreamUrl(), substreamUrl)
                    || sameUri(camera.url(), substreamUrl) || sameUri(camera.substreamUrl(), url)) {
                throw new IllegalArgumentException("another camera already uses that RTSP URL");
            }
        }
        return new Validated(name, url, substreamUrl, draft.record(), draft.ai(), draft.enabled());
    }

    private static String validateUrl(URI uri, String label) {
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("rtsp") && !scheme.equals("rtsps")) {
            throw new IllegalArgumentException(label + " must start with rtsp:// or rtsps://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(label + " is missing a host");
        }
        return uri.toString();
    }

    private static boolean sameUri(URI left, String right) {
        return left != null && right != null && left.toString().equals(right);
    }

    private static URI uri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
