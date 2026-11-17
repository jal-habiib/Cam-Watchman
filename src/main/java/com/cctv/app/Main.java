package com.cctv.app;

import java.nio.file.Path;
import java.util.Map;
import javafx.application.Application;

/**
 * Launcher entry point.
 *
 * <p>JavaFX refuses to start when the main class itself extends {@link Application} and the
 * modules are on the classpath (“JavaFX runtime components are missing”), so the app is started
 * through this plain class instead.
 *
 * <p>Phase 8c: the log directory is decided here, before any logger initialises, so a packaged
 * build writes next to its data folder instead of into the developer's home directory.
 *
 * <p>Phase 8e: a packaged launcher cannot receive {@code -D} JVM options, so the handful of
 * diagnostics switches are also accepted as {@code --key=value} application arguments.
 */
public final class Main {

    private static final Map<String, String> ARGUMENTS = Map.of(
            "--config", "cctv.config",
            "--logs", "cctv.logs.dir",
            "--model", "cctv.model",
            "--snapshot", "cctv.snapshot",
            "--snapshot-delay", "cctv.snapshot.delay",
            "--layout-focus", "cctv.layout.focus",
            "--open-search", "cctv.open.search",
            "--manage-cameras", "cctv.open.manager",
            "--soak", "cctv.soak");

    private Main() {
    }

    public static void main(String[] args) {
        applyArguments(args);
        if (System.getProperty("cctv.logs.dir") == null
                || System.getProperty("cctv.logs.dir").isBlank()) {
            System.setProperty("cctv.logs.dir", defaultLogsDir().toString());
        }
        // Phase 8c: a packaged launcher has no console, so uncaught failures must reach the log file.
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                org.slf4j.LoggerFactory.getLogger("uncaught").error(
                        "uncaught error on thread {}", thread.getName(), failure));
        Application.launch(CamWatchmanApp.class, args);
    }

    private static void applyArguments(String[] args) {
        for (String argument : args) {
            int equals = argument.indexOf('=');
            if (!argument.startsWith("--") || equals < 0) {
                continue;
            }
            String property = ARGUMENTS.get(argument.substring(0, equals));
            if (property != null) {
                System.setProperty(property, argument.substring(equals + 1));
            }
        }
    }

    private static Path defaultLogsDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".cam-watchman")
                : Path.of(localAppData, "CamWatchman");
        return base.resolve("logs");
    }
}
