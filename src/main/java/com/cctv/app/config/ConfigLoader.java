package com.cctv.app.config;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and validates {@code cameras.properties} (plan 1b).
 *
 * <p>The file is parsed line by line — {@code key=value}, {@code #} comments, blank lines ignored —
 * rather than with {@link java.util.Properties}, because property escaping turns every Windows
 * backslash in a path into an escape sequence. Values are taken verbatim and trimmed.
 *
 * <p>Validation is strict: an unusable camera is rejected with the full list of problems instead of
 * silently starting with fewer cameras. A file with no cameras at all is valid — that is the
 * first-run state, where the user adds cameras in the app. Unknown keys are ignored so the file can
 * grow between phases; known keys with bad values are always errors.
 */
public final class ConfigLoader {

    /** Plan: segment length is configurable between 10 and 30 seconds. */
    public static final int MIN_SEGMENT_SECONDS = 10;
    public static final int MAX_SEGMENT_SECONDS = 30;

    public static final int DEFAULT_SEGMENT_SECONDS = 15;
    public static final int DEFAULT_RETENTION_DAYS = 7;
    public static final double DEFAULT_RETENTION_GB = 40;
    public static final double DEFAULT_MIN_FREE_GB = 10;
    public static final double DEFAULT_AI_RATE_FPS = 1.0;

    private static final String PREFIX = "cameras.";
    private static final Pattern CAMERA_KEY = Pattern.compile("^cameras\\.(\\d+)\\.([a-z_]+)$");
    private static final Set<String> URL_SCHEMES = Set.of("rtsp", "rtsps");

    private ConfigLoader() {
    }

    /** Base data directory used when {@code base.data.dir} is empty. */
    public static Path defaultBaseDataDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = (localAppData != null && !localAppData.isBlank())
                ? Path.of(localAppData)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return root.resolve("CamWatchman").toAbsolutePath().normalize();
    }

    /**
     * Loads the configuration file.
     *
     * @param configFile    path to {@code cameras.properties}
     * @param defaultBaseDir used when the file leaves {@code base.data.dir} empty
     * @throws ConfigException when the file is missing, unreadable, or invalid
     */
    public static AppConfig load(Path configFile, Path defaultBaseDir) {
        Map<String, String> values;
        try {
            values = readValues(configFile);
        } catch (IOException e) {
            throw new ConfigException("cannot read " + configFile + ": " + e.getMessage());
        }
        return parse(values, configFile, defaultBaseDir);
    }

    /**
     * First-run configuration when no {@code cameras.properties} exists yet: an empty camera list
     * plus the defaults, so the app can start and let the user add a camera in the UI.
     */
    public static AppConfig emptyConfig(Path configFile, Path defaultBaseDir) {
        return new AppConfig(configFile, defaultBaseDir.toAbsolutePath().normalize(), List.of(),
                DEFAULT_SEGMENT_SECONDS, DEFAULT_RETENTION_DAYS,
                gbToBytes(DEFAULT_RETENTION_GB), gbToBytes(DEFAULT_MIN_FREE_GB), DEFAULT_AI_RATE_FPS);
    }

    /** Parses already-read key/value pairs; separated from I/O so it is unit-testable. */
    public static AppConfig parse(Map<String, String> values, Path configFile, Path defaultBaseDir) {
        List<String> problems = new ArrayList<>();

        List<CameraConfig> cameras = parseCameras(values, problems);

        Path base = parseBaseDir(values, configFile, defaultBaseDir, problems);

        int segmentSeconds = parseInt(values, "recording.segment.seconds", DEFAULT_SEGMENT_SECONDS, problems);
        if (segmentSeconds < MIN_SEGMENT_SECONDS || segmentSeconds > MAX_SEGMENT_SECONDS) {
            problems.add("recording.segment.seconds must be between " + MIN_SEGMENT_SECONDS
                    + " and " + MAX_SEGMENT_SECONDS + " (got " + segmentSeconds + ")");
        }

        int retentionDays = parseInt(values, "retention.days", DEFAULT_RETENTION_DAYS, problems);
        if (retentionDays <= 0) {
            problems.add("retention.days must be positive (got " + retentionDays + ")");
        }

        double retentionGb = parseDouble(values, "retention.size.gb", DEFAULT_RETENTION_GB, problems);
        if (retentionGb <= 0) {
            problems.add("retention.size.gb must be positive (got " + retentionGb + ")");
        }

        double minFreeGb = parseDouble(values, "retention.min.free.gb", DEFAULT_MIN_FREE_GB, problems);
        if (minFreeGb < 0) {
            problems.add("retention.min.free.gb must not be negative (got " + minFreeGb + ")");
        }

        double aiRate = parseDouble(values, "ai.rate.fps", DEFAULT_AI_RATE_FPS, problems);
        if (aiRate <= 0 || aiRate > 60) {
            problems.add("ai.rate.fps must be in (0, 60] (got " + aiRate + ")");
        }

        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }

        return new AppConfig(configFile, base, cameras, segmentSeconds, retentionDays,
                gbToBytes(retentionGb), gbToBytes(minFreeGb), aiRate);
    }

    // ---------------------------------------------------------------- parsing

    private static Map<String, String> readValues(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("file does not exist");
        }
        Map<String, String> values = new LinkedHashMap<>();
        int lineNo = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IOException("line " + lineNo + " is not key=value");
            }
            values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return values;
    }

    private static List<CameraConfig> parseCameras(Map<String, String> values, List<String> problems) {
        Map<Integer, Map<String, String>> byId = new TreeMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            Matcher m = CAMERA_KEY.matcher(e.getKey());
            if (!m.matches()) {
                continue;
            }
            int index = Integer.parseInt(m.group(1));
            byId.computeIfAbsent(index, k -> new LinkedHashMap<>()).put(m.group(2), e.getValue());
        }

        List<CameraConfig> out = new ArrayList<>();
        Set<Long> seenIds = new java.util.HashSet<>();
        for (Map.Entry<Integer, Map<String, String>> e : byId.entrySet()) {
            int index = e.getKey();
            Map<String, String> c = e.getValue();
            String where = "cameras." + index;
            int problemsBefore = problems.size();

            long id = parseIndexId(index, c.get("id"), where, problems);
            if (id <= 0) {
                continue;
            }
            if (!seenIds.add(id)) {
                problems.add(where + ": duplicate camera id " + id);
                continue;
            }

            String name = c.getOrDefault("name", "").trim();
            if (name.isEmpty()) {
                problems.add(where + ": name is required");
            }

            URI main = parseUrl(c.get("url"), where + ".url", problems);
            URI sub = parseUrl(c.get("substream_url"), where + ".substream_url", problems);
            boolean record = parseBool(c.get("record"), false, where + ".record", problems);
            boolean ai = parseBool(c.get("ai"), false, where + ".ai", problems);

            if (main == null && sub == null) {
                problems.add(where + ": needs url and/or substream_url");
            }
            if ((record || ai) && main == null) {
                problems.add(where + ": record/ai enabled but no main-stream url");
            }

            if (problems.size() > problemsBefore) {
                continue; // this camera is unusable; its problems are already reported
            }
            out.add(new CameraConfig(id, name, main, sub, record, ai));
        }
        out.sort(Comparator.comparingLong(CameraConfig::id));
        return out;
    }

    private static long parseIndexId(int index, String raw, String where, List<String> problems) {
        if (raw == null || raw.isBlank()) {
            return index;
        }
        try {
            long id = Long.parseLong(raw.trim());
            if (id <= 0) {
                problems.add(where + ".id must be positive (got " + raw + ")");
                return -1;
            }
            return id;
        } catch (NumberFormatException ex) {
            problems.add(where + ".id is not a number: " + raw);
            return -1;
        }
    }

    private static URI parseUrl(String raw, String where, List<String> problems) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!URL_SCHEMES.contains(scheme)) {
                problems.add(where + ": unsupported scheme '" + scheme + "' (expected rtsp or rtsps)");
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                problems.add(where + ": URL has no host — " + Redaction.redact(raw));
                return null;
            }
            return uri;
        } catch (URISyntaxException ex) {
            problems.add(where + ": malformed URL — " + Redaction.redact(raw));
            return null;
        }
    }

    private static boolean parseBool(String raw, boolean dflt, String where, List<String> problems) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("yes") || v.equals("1")) {
            return true;
        }
        if (v.equals("false") || v.equals("no") || v.equals("0")) {
            return false;
        }
        problems.add(where + " must be true or false (got '" + raw + "')");
        return dflt;
    }

    private static Path parseBaseDir(Map<String, String> values, Path configFile, Path defaultBaseDir,
                                     List<String> problems) {
        String raw = values.getOrDefault("base.data.dir", "").trim();
        if (raw.isEmpty()) {
            return defaultBaseDir.toAbsolutePath().normalize();
        }
        try {
            Path p = Path.of(raw);
            if (!p.isAbsolute()) {
                Path parent = configFile.toAbsolutePath().getParent();
                p = (parent == null ? Path.of("") : parent).resolve(p);
            }
            return p.toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            problems.add("base.data.dir is not a valid path: " + raw);
            return defaultBaseDir.toAbsolutePath().normalize();
        }
    }

    private static int parseInt(Map<String, String> values, String key, int dflt, List<String> problems) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            problems.add(key + " is not a whole number: " + raw);
            return dflt;
        }
    }

    private static double parseDouble(Map<String, String> values, String key, double dflt, List<String> problems) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            problems.add(key + " is not a number: " + raw);
            return dflt;
        }
    }

    private static long gbToBytes(double gb) {
        return Math.round(gb * 1024.0 * 1024.0 * 1024.0);
    }
}
