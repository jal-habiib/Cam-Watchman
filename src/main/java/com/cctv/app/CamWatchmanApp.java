package com.cctv.app;

import com.cctv.app.alert.AlertService;
import com.cctv.app.config.AppConfig;
import com.cctv.app.config.CameraConfig;
import com.cctv.app.config.ConfigException;
import com.cctv.app.config.ConfigLoader;
import com.cctv.app.config.Redaction;
import com.cctv.app.config.RigConfigTemplate;
import com.cctv.app.db.FreeSpace;
import com.cctv.app.db.CameraStore;
import com.cctv.app.db.RecordingRepository;
import com.cctv.app.db.RetentionService;
import com.cctv.app.count.CountLineStore;
import com.cctv.app.count.CountService;
import com.cctv.app.db.ZoneStore;
import com.cctv.app.event.SimpleEventBus;
import com.cctv.app.ui.AlertPopup;
import com.cctv.app.ui.CameraCell;
import com.cctv.app.ui.CameraForm;
import com.cctv.app.ui.CameraManagerView;
import com.cctv.app.ui.DetectionOverlay;
import com.cctv.app.ui.SearchView;
import com.cctv.app.ui.CountsView;
import com.cctv.app.ui.LineEditor;
import com.cctv.app.ui.ZoneEditor;
import com.cctv.app.video.EngineVideoView;
import com.cctv.app.video.FfmpegDisplayView;
import com.cctv.contract.Detection;
import com.cctv.contract.EventBus;
import com.cctv.contract.IntrusionEvent;
import com.cctv.contract.StreamState;
import com.cctv.contract.StreamStatusEvent;
import com.cctv.contract.ZoneDefinition;
import com.cctv.app.engine.EngineHandle;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX desktop application shell. Loads the local camera configuration, builds the four-camera
 * wall, and coordinates per-camera capture/AI/recording runtimes, event overlays, alerts, search,
 * counting, camera management and local storage. Runtime startup and teardown stay off the FX thread
 * wherever they can block; camera events are routed by camera id.
 */
public final class CamWatchmanApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(CamWatchmanApp.class);

    private final SimpleEventBus bus = new SimpleEventBus();
    private final Map<Long, CameraCell> cells = new LinkedHashMap<>();
    private final List<FfmpegDisplayView> videoViews = new ArrayList<>();
    private final Map<Long, FfmpegDisplayView> videoByCamera = new LinkedHashMap<>();
    private final List<CameraCell> cellOrder = new ArrayList<>();
    private GridPane wallGrid;
    private BorderPane root;
    private SearchView searchView;
    private int focusedIndex = -1;
    private AppConfig config;
    private Stage stage;
    private Label storageLabel;
    private RecordingRepository repository;
    private RetentionService retention;
    private ScheduledExecutorService storageScheduler;
    private AlertService alertService;
    private AlertPopup alertPopup;
    private ZoneStore zoneStore;
    private ZoneEditor zoneEditor;
    private CountLineStore countLines;
    private CountService countService;
    private CountsView countsView;
    private LineEditor lineEditor;
    private StackPane wallStack;
    private final Map<Long, EngineHandle> pipelines = new ConcurrentHashMap<>();
    private final Map<Long, java.util.Set<java.nio.file.Path>> openSegments = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Long> segmentStartUtc = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, EngineVideoView> engineViews = new ConcurrentHashMap<>();
    private final Map<Long, DetectionOverlay> detectionOverlays = new ConcurrentHashMap<>();
    private final AtomicLong engineGeneration = new AtomicLong();
    private final AtomicBoolean storageRefreshPending = new AtomicBoolean();
    private final Object runtimeLock = new Object();
    private com.cctv.app.label.ClipLabeler clipLabeler;
    private com.cctv.app.alert.ZoneConfirmer zoneConfirmer;
    private EventBus.Subscription trackingSubscription;
    private ExecutorService engineStart;
    private Label detectionLabel;
    private final Map<String, Detection> lastDetectionByClass = new ConcurrentHashMap<>();
    private final AtomicLong detectionsSeen = new AtomicLong();
    private final Map<Long, String> lastStatusByCamera = new ConcurrentHashMap<>();
    private CameraStore cameraStore;
    private CameraManagerView cameraManager;
    private volatile List<CameraConfig> activeCameras;

    private record StorageSnapshot(long usedBytes, long freeBytes, boolean recordingAllowed,
                                   long droppedCommands, int queuedCommands, boolean databaseFailed) { }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        Path configPath = resolveConfigPath();
        boolean firstRun = !java.nio.file.Files.isRegularFile(configPath);
        if (firstRun) {
            // Fresh download: there is no cameras.properties yet. Start with an empty wall and let
            // the user add their own camera instead of failing on a developer-only test config.
            config = ConfigLoader.emptyConfig(configPath, ConfigLoader.defaultBaseDataDir());
            log.info("first run: no configuration at {} - starting in setup mode", configPath);
        } else {
            try {
                config = ConfigLoader.load(configPath, ConfigLoader.defaultBaseDataDir());
            } catch (ConfigException e) {
                log.warn("configuration rejected: {}", e.problems());
                showConfigError(configPath, e);
                return;
            }
        }

        log.info("configuration loaded from {} ({} cameras, base dir {}, segment {} s)",
                configPath, config.cameras().size(), config.baseDataDir(), config.segmentSeconds());
        for (var cam : config.cameras()) {
            log.info("camera {} '{}' display={} main={} ai={} record={}", cam.id(), cam.name(),
                    cam.displayUrl() == null ? "-" : Redaction.redact(cam.displayUrl().toString()),
                    cam.hasMain() ? Redaction.redact(cam.mainUrl().toString()) : "-",
                    cam.ai(), cam.record());
        }

        startStorage();

        Scene scene = new Scene(buildWall(), 1100, 700);
        primaryStage.setTitle(activeCameras.isEmpty()
                ? "Cam Watchman — add your camera"
                : "Cam Watchman — " + activeCameras.size() + " cameras");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> {
            if (System.getProperty("cctv.soak") != null) {
                // A long soak must not be ended by a stray click on the window's close button.
                e.consume();
                log.info("soak mode: window close ignored (stop the soak script to end the run)");
                return;
            }
            Platform.exit();
        });
        primaryStage.show();

        // Start all live views after the wall is visible.
        for (FfmpegDisplayView video : videoViews) {
            video.start();
        }
        engineViews.values().forEach(EngineVideoView::start);
        detectionOverlays.values().forEach(DetectionOverlay::start);
        if (activeCameras.isEmpty()) {
            // Setup mode: no camera yet. Open the manager so the user sees "+ Add camera" and the
            // RTSP URL field straight away.
            after(2, () -> {
                openCameraManager();
                log.info("setup: opened the camera manager to add the first camera");
            });
        }

        scene.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && cameraManager != null
                    && cameraManager.isVisible()) {
                cameraManager.hide();
            } else if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && lineEditor != null
                    && lineEditor.isOpen()) {
                lineEditor.close();
            } else if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && countsView != null
                    && countsView.isVisible()) {
                countsView.hide();
            } else if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && zoneEditor != null
                    && zoneEditor.isOpen()) {
                zoneEditor.close();
            } else if (event.getCode() == javafx.scene.input.KeyCode.F && event.isControlDown()) {
                showSearch(null);
            } else if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && searchView != null
                    && wallStack != null && wallStack.getChildren().contains(searchView)) {
                showWall();
            } else if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && focusedIndex >= 0) {
                toggleFocus(focusedIndex);
            }
        });
        String openSearch = System.getProperty("cctv.open.search");
        if (openSearch != null) {
            showSearch(openSearch);
        }
        if (System.getProperty("cctv.nav.demo") != null) {
            // Runtime acceptance matrix: visit every overlay and return to the wall, logging each hop
            // so the transitions are provable in the run log (one snapshot shows the final wall).
            after(2, () -> {
                showSearch("person");
                log.info("nav: search open");
            });
            after(3.5, () -> {
                showWall();
                log.info("nav: back to wall");
            });
            after(5, () -> {
                openCameraManager();
                log.info("nav: cameras open");
            });
            after(6.5, () -> {
                cameraManager.hide();
                log.info("nav: back to wall");
            });
            after(8, () -> {
                showCounts();
                log.info("nav: counts open");
            });
            after(9.5, () -> {
                countsView.hide();
                log.info("nav: back to wall");
            });
        }        if (System.getProperty("cctv.open.manager") != null) {
            after(2, this::openCameraManager);
        }
        String demoCamera = System.getProperty("cctv.cameras.demo");
        if ("form".equalsIgnoreCase(demoCamera)) {
            // Headless acceptance path for the edit form: manager behind it, form on top.
            after(4, this::openCameraManager);
            after(5, () -> {
                CameraStore.Camera existing = cameraStore.all().stream()
                        .filter(camera -> camera.id() != 1)
                        .findFirst()
                        .orElse(null);
                CameraForm.open(primaryStage, cameraStore, existing,
                        message -> log.info("camera form saved: {}", message));
            });
        }        if ("add".equalsIgnoreCase(demoCamera)) {
            // Headless acceptance path for the manager: add a camera, then rebuild the wall so the
            // evidence shows the new tile next to the existing ones.
            after(5, () -> {
                try {
                    List<CameraStore.Camera> existing = cameraStore.all();
                    if (!existing.isEmpty()) {
                        // The wall is capped at four cameras, so the demo swaps the last tile out.
                        cameraStore.remove(existing.get(existing.size() - 1).id());
                    }
                    cameraStore.add(new CameraStore.Draft("Demo extra", null,
                            java.net.URI.create("rtsp://127.0.0.1:8554/cam4_sub"), false, false, true));
                    rebuildWall();
                    log.info("demo camera added and wall rebuilt");
                } catch (RuntimeException e) {
                    log.warn("demo camera failed: {}", e.toString());
                }
            });
        }
        String focus = System.getProperty("cctv.layout.focus");
        if (focus != null && !focus.isBlank()) {
            for (int i = 0; i < cellOrder.size(); i++) {
                if (String.valueOf(cellOrder.get(i).cameraId()).equals(focus)) {
                    toggleFocus(i);
                }
            }
        }
        if (System.getProperty("cctv.alert.demo") != null) {
            startAlertDemo();
        }
        if (System.getProperty("cctv.counts.demo") != null) {
            startCountingDemo();
        }
        String lineEditorCamera = System.getProperty("cctv.line.editor");
        if (lineEditorCamera != null && !lineEditorCamera.isBlank()) {
            long cameraId = Long.parseLong(lineEditorCamera.trim());
            after(3, () -> {
                openLines(cameraId);
                if (lineEditor != null && System.getProperty("cctv.line.demo") != null) {
                    lineEditor.addDemoLine("Front walk", 0.15, 0.65, 0.85, 0.65);
                }
                if (lineEditor != null && System.getProperty("cctv.line.autosave") != null) {
                    after(2, () -> {
                        if (lineEditor.save()) {
                            openLines(cameraId);
                        }
                    });
                }
            });
        }
        String zoneEditorCamera = System.getProperty("cctv.zone.editor");
        if (zoneEditorCamera != null && !zoneEditorCamera.isBlank()) {
            long cameraId = Long.parseLong(zoneEditorCamera.trim());
            after(3, () -> {
                openZones(cameraId);
                if (System.getProperty("cctv.zone.demo") != null) {
                    zoneEditor.addDemoZone("Front gate", 0.55, 0.35, 0.30, 0.45);
                }
                if (System.getProperty("cctv.zone.autosave") != null) {
                    after(2, () -> {
                        if (zoneEditor.save()) {
                            // reload from the database so the evidence shows persisted state
                            openZones(cameraId);
                        }
                        if (System.getProperty("cctv.zone.confirm.demo") != null) {
                            zoneEditor.close();
                            // two person detections inside the saved zone, 1.2 s apart: exactly the
                            // confirmation rule ZoneConfirmer implements (plan 5b).
                            after(1, () -> bus.publish(new com.cctv.contract.Detection(cameraId,
                                    "person", 0.88f, 0.62, 0.45, 0.78, 0.70, System.currentTimeMillis())));
                            after(3, () -> bus.publish(new com.cctv.contract.Detection(cameraId,
                                    "person", 0.91f, 0.63, 0.46, 0.79, 0.71, System.currentTimeMillis())));
                        }
                    });
                }
            });
        }

        // Each camera runtime publishes StreamStatusEvent; the wall badge is updated by camera id.
        bus.subscribe(StreamStatusEvent.class, state -> {
            log.info("stream state: camera {} {} ({})", state.cameraId(), state.state(), state.detail());
            lastStatusByCamera.put(state.cameraId(), state.state()
                    + (state.detail() == null || state.detail().isBlank() ? "" : " - " + state.detail()));
            Platform.runLater(() -> {
                CameraCell cell = cells.get(state.cameraId());
                if (cell != null) {
                    cell.setState(state);
                }
            });
        });
        publishInitialStates();

        // Engine detections: the listener only records and hops to the FX thread; never I/O here.
        bus.subscribe(Detection.class, detection -> {
            detectionsSeen.incrementAndGet();
            lastDetectionByClass.put(detection.objectClass(), detection);
            Platform.runLater(() -> {
                DetectionOverlay overlay = detectionOverlays.get(detection.cameraId());
                if (overlay != null) {
                    overlay.accept(detection);
                }
                refreshDetectionHint();
            });
        });
        // Engine tracking (plan 7a): one snapshot per inference frame; id labels on the overlay.
        trackingSubscription = bus.subscribe(com.cctv.engine.TrackingUpdate.class, update -> {
            Platform.runLater(() -> {
                DetectionOverlay overlay = detectionOverlays.get(update.cameraId());
                if (overlay != null) {
                    overlay.acceptTracking(update);
                }
            });
        });
        startEngine();
        snapshotIfRequested(scene);
    }

    // ------------------------------------------------------------------ UI

    private BorderPane buildWall() {
        root = new BorderPane();
        root.setStyle("-fx-background-color:#0d0d0d;");
        root.setTop(header());
        // Phase 5d: the alert card is an overlay layer above the wall, not a second window, so it
        // stays inside the app scene (and inside screenshot evidence).
        alertPopup = new AlertPopup();
        zoneEditor = new ZoneEditor(zoneStore, saved -> {
            log.info("zones saved for {}", saved);
            refreshOverlays();
        });
        wallStack = new StackPane(wall(), alertPopup, zoneEditor);
        root.setCenter(wallStack);
        root.setBottom(footer());
        return root;
    }

    private HBox header() {
        Label title = new Label("CAM WATCHMAN");
        title.setStyle("-fx-text-fill:#f2f2f2; -fx-font-size:16px; -fx-font-weight:bold; -fx-letter-spacing:2px;");
        Label subtitle = new Label("4-camera wall · independent AI + recording per camera");
        subtitle.setStyle("-fx-text-fill:#7a7a7a; -fx-font-size:11px;");
        VBox titles = new VBox(1, title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label phase = new Label("Local monitoring · AI + recording");
        phase.setStyle("-fx-text-fill:#8a8a8a; -fx-font-size:11px;");

        Button search = new Button("Search clips");
        search.setStyle("-fx-background-color:#1b1b1b; -fx-text-fill:#e0e0e0; -fx-border-color:#333;"
                + " -fx-border-radius:4; -fx-background-radius:4; -fx-font-size:11px;");
        search.setOnAction(event -> showSearch(null));

        Button zones = new Button("Zones");
        zones.setStyle("-fx-background-color:#1b1b1b; -fx-text-fill:#e0e0e0; -fx-border-color:#333;"
                + " -fx-border-radius:4; -fx-background-radius:4; -fx-font-size:11px;");
        zones.setOnAction(event -> openZones(null));

        Button lines = new Button("Lines");
        lines.setStyle("-fx-background-color:#1b1b1b; -fx-text-fill:#e0e0e0; -fx-border-color:#333;"
                + " -fx-border-radius:4; -fx-background-radius:4; -fx-font-size:11px;");
        lines.setOnAction(event -> openLines(null));

        Button counts = new Button("Counts");
        counts.setStyle("-fx-background-color:#1b1b1b; -fx-text-fill:#e0e0e0; -fx-border-color:#333;"
                + " -fx-border-radius:4; -fx-background-radius:4; -fx-font-size:11px;");
        counts.setOnAction(event -> showCounts());

        Button camerasButton = new Button("Cameras");
        camerasButton.setStyle("-fx-background-color:#1b1b1b; -fx-text-fill:#e0e0e0; -fx-border-color:#333;"
                + " -fx-border-radius:4; -fx-background-radius:4; -fx-font-size:11px;");
        camerasButton.setOnAction(event -> openCameraManager());

        HBox header = new HBox(12, titles, spacer, search, zones, lines, counts, camerasButton, phase);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 14, 10, 14));
        header.setStyle("-fx-border-color:transparent transparent #222 transparent; -fx-border-width:0 0 1 0;");
        return header;
    }

    // ------------------------------------------------------------ cameras (SQLite)

    /** Cameras on the wall: the stored list once storage is up, else the file configuration. */
    private List<CameraConfig> cameras() {
        List<CameraConfig> current = activeCameras;
        return current != null ? current : config.cameras();
    }

    private void refreshActiveCameras() {
        if (cameraStore == null) {
            activeCameras = config.cameras();
            return;
        }
        List<CameraConfig> list = new ArrayList<>();
        for (CameraStore.Camera camera : cameraStore.enabled()) {
            list.add(camera.toConfig());
        }
        activeCameras = List.copyOf(list);
    }

    private String statusTextFor(long cameraId) {
        String status = lastStatusByCamera.get(cameraId);
        return status == null ? "not started" : status;
    }

    private void openCameraManager() {
        if (cameraManager == null) {
            return;
        }
        cameraManager.refresh();
        cameraManager.show();
        cameraManager.toFront();
        cameraManager.requestFocus();
    }

    /**
     * Rebuilds the wall after a camera change: closes the live viewers (FFmpeg display workers and the engine
     * display tap) before the old camera list is forgotten, then starts only what the new list
     * enables. Recording history in SQLite is untouched.
     */
    private void rebuildWall() {
        if (wallStack == null) {
            return;
        }
        synchronized (runtimeLock) {
            engineGeneration.incrementAndGet();
            pipelines.values().forEach(EngineHandle::close);
            pipelines.clear();
        }
        if (engineStart != null) {
            engineStart.shutdownNow();
            engineStart = null;
        }
        for (FfmpegDisplayView video : videoViews) {
            video.close();
        }
        videoViews.clear();
        videoByCamera.clear();
        engineViews.values().forEach(EngineVideoView::close);
        engineViews.clear();
        detectionOverlays.values().forEach(DetectionOverlay::close);
        detectionOverlays.clear();
        cells.clear();
        cellOrder.clear();
        focusedIndex = -1;
        lastStatusByCamera.clear();
        refreshActiveCameras();
        wallStack.getChildren().set(0, wall());
        for (FfmpegDisplayView video : videoViews) {
            video.start();
        }
        engineViews.values().forEach(EngineVideoView::start);
        detectionOverlays.values().forEach(DetectionOverlay::start);
        startEngine();
        refreshOverlays();
        publishInitialStates();
        log.info("camera wall rebuilt: {} camera(s) enabled", activeCameras.size());
    }

    private GridPane wall() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        for (int i = 0; i < 2; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(50);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
            RowConstraints rc = new RowConstraints();
            rc.setPercentHeight(50);
            rc.setVgrow(Priority.ALWAYS);
            grid.getRowConstraints().add(rc);
        }

        int index = 0;
        this.wallGrid = grid;
        for (var camera : cameras()) {
            CameraCell cell = new CameraCell(camera);
            cells.put(camera.id(), cell);
            if (camera.hasSubstream() && !camera.hasMain()) {
                // Display-only cameras decode their substreams through the bundled FFmpeg runtime.
                FfmpegDisplayView video = new FfmpegDisplayView(camera.id(), camera.substreamUrl(), bus::publish);
                cell.setVideo(video);
                videoViews.add(video);
                videoByCamera.put(camera.id(), video);
            } else if (camera.hasMain() && camera.needsMainStream()) {
                long cameraId = camera.id();
                EngineVideoView video = new EngineVideoView(() -> {
                    EngineHandle handle = pipelines.get(cameraId);
                    return handle == null ? java.util.Optional.<com.cctv.contract.Frame>empty()
                            : handle.latestFrame();
                });
                engineViews.put(cameraId, video);
                DetectionOverlay overlay = new DetectionOverlay();
                overlay.setManaged(false);
                overlay.widthProperty().bind(video.widthProperty());
                overlay.heightProperty().bind(video.heightProperty());
                detectionOverlays.put(cameraId, overlay);
                StackPane layer = new StackPane(video, overlay);
                cell.setVideo(layer);
            }
            grid.add(cell, index % 2, index / 2);
            cellOrder.add(cell);
            final int cellIndex = index;
            cell.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    toggleFocus(cellIndex);
                }
            });
            index++;
        }
        // Fewer than four cameras configured: keep the grid balanced with empty cells.
        while (index < 4) {
            Button add = new Button("+  Add camera");
            add.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            add.setStyle("-fx-background-color:transparent; -fx-text-fill:#6b7681; -fx-font-size:13px;"
                    + " -fx-border-color:#242a30; -fx-border-style:dashed; -fx-border-radius:6;"
                    + " -fx-background-radius:6;");
            add.setOnAction(event -> openCameraManager());
            grid.add(add, index % 2, index / 2);
            index++;
        }
        return grid;
    }

    /**
     * Phase 6b: 1x1 focus on one camera and back to 2x2. Only grid positions and visibility
     * change — the camera's player keeps running, so the layout toggle never restarts a stream.
     */
    private void showSearch(String prefill) {
        if (searchView == null) {
            searchView = new SearchView(config.databaseFile(), this::showWall);
        }
        if (prefill != null && !prefill.isBlank()) {
            searchView.setObjectText(prefill);
        }
        searchView.runSearch();
        // Keep the wall and its tool overlays mounted. Zones and the line editor are children
        // of wallStack; replacing root.center with SearchView detached them from the scene,
        // so their navigation buttons appeared to do nothing while Search was open.
        if (!wallStack.getChildren().contains(searchView)) {
            wallStack.getChildren().add(1, searchView);
        }
        searchView.toFront();
        searchView.requestFocus();
    }

    private void showWall() {
        if (wallStack != null && searchView != null) {
            wallStack.getChildren().remove(searchView);
        }
        root.setCenter(wallStack);
    }

    // ------------------------------------------------------------------- zones

    /** Phase 5c: opens the zone editor on a frozen frame of one camera. */
    private void openZones(Long requested) {
        if (zoneStore == null || zoneEditor == null) {
            return;
        }
        CameraConfig camera = pickZoneCamera(requested);
        if (camera == null) {
            return;
        }
        BufferedImage frame = null;
        FfmpegDisplayView view = videoByCamera.get(camera.id());
        if (view != null) {
            frame = view.snapshotImage().orElse(null);
        }
        if (frame == null) {
            frame = demoSnapshot();
        }
        zoneEditor.open(camera.id(), camera.name(), frame);
    }

    private CameraConfig pickZoneCamera(Long requested) {
        for (var camera : cameras()) {
            if (requested != null && camera.id() == requested) {
                return camera;
            }
        }
        for (var camera : cameras()) {
            if (!camera.hasMain()) {
                return camera;
            }
        }
        return cameras().isEmpty() ? null : cameras().get(0);
    }

    // ------------------------------------------------------------------- counting

    /** Phase 7c: shows today's counts overlay. */
    private void showCounts() {
        if (countsView == null) {
            return;
        }
        if (lineEditor != null && lineEditor.isOpen()) {
            lineEditor.close();
        }
        countsView.show();
        countsView.toFront();
        countsView.requestFocus();
    }

    /** Phase 7b: opens the counting-line editor on a frozen frame of one camera. */
    private void openLines(Long requested) {
        if (countLines == null || lineEditor == null) {
            return;
        }
        CameraConfig camera = pickZoneCamera(requested);
        if (camera == null) {
            return;
        }
        BufferedImage frame = null;
        FfmpegDisplayView view = videoByCamera.get(camera.id());
        if (view != null) {
            frame = view.snapshotImage().orElse(null);
        }
        if (frame == null) {
            frame = demoSnapshot();
        }
        if (countsView != null) {
            countsView.hide();
        }
        if (cameraManager != null) {
            cameraManager.hide();
        }
        lineEditor.open(camera.id(), camera.name(), frame);
        lineEditor.toFront();
        lineEditor.requestFocus();
    }

    /**
     * Phase 7b evidence switch: seeds one counting line on a display-only camera and replays a few
     * synthetic crossings through the real bus path, so the counts panel can be captured headlessly.
     */
    private void startCountingDemo() {
        String property = System.getProperty("cctv.counts.demo");
        if (property == null) {
            return;
        }
        CameraConfig camera = pickZoneCamera(null);
        if (camera == null) {
            return;
        }
        after(2, () -> {
            try {
                countLines.replaceForCamera(camera.id(), java.util.List.of(
                        new com.cctv.app.db.LineDraft("Front walk", 0.15, 0.65, 0.85, 0.65, true)));
                long lineId = countLines.forCamera(camera.id()).stream()
                        .mapToLong(com.cctv.contract.CountLine::id).findFirst().orElse(-1);
                if (lineId <= 0) {
                    log.warn("counting demo: no line id after save");
                    return;
                }
                long now = System.currentTimeMillis();
                bus.publish(new com.cctv.contract.LineCrossingEvent(camera.id(), lineId, 11,
                        com.cctv.contract.LineCrossingEvent.Direction.IN, now - 90_000));
                bus.publish(new com.cctv.contract.LineCrossingEvent(camera.id(), lineId, 12,
                        com.cctv.contract.LineCrossingEvent.Direction.IN, now - 60_000));
                bus.publish(new com.cctv.contract.LineCrossingEvent(camera.id(), lineId, 12,
                        com.cctv.contract.LineCrossingEvent.Direction.OUT, now - 30_000));
                log.info("counting demo: line {} on camera {} with 2 IN / 1 OUT", lineId, camera.id());
            } catch (java.sql.SQLException e) {
                log.warn("counting demo failed: {}", e.toString());
            }
        });
        after(5, this::showCounts);
    }

    private void toggleFocus(int index) {
        focusedIndex = focusedIndex == index ? -1 : index;
        applyLayout();
    }

    private void applyLayout() {
        for (int i = 0; i < cellOrder.size(); i++) {
            CameraCell cell = cellOrder.get(i);
            GridPane.setColumnIndex(cell, i % 2);
            GridPane.setRowIndex(cell, i / 2);
            GridPane.setColumnSpan(cell, 1);
            GridPane.setRowSpan(cell, 1);
            cell.setVisible(true);
            cell.setManaged(true);
        }
        if (focusedIndex < 0) {
            log.info("layout: 2x2 (streams untouched)");
            return;
        }
        CameraCell focused = cellOrder.get(focusedIndex);
        GridPane.setColumnIndex(focused, 0);
        GridPane.setRowIndex(focused, 0);
        GridPane.setColumnSpan(focused, 2);
        GridPane.setRowSpan(focused, 2);
        for (int i = 0; i < cellOrder.size(); i++) {
            if (i != focusedIndex) {
                cellOrder.get(i).setVisible(false);
                cellOrder.get(i).setManaged(false);
            }
        }
        log.info("layout: 1x1 camera {} (streams untouched)", focused.cameraId());
    }

    private HBox footer() {
        Label configLabel = new Label("config: " + config.configFile());
        configLabel.setStyle("-fx-text-fill:#6f6f6f; -fx-font-size:10px;");
        Label dataLabel = new Label("data: " + config.baseDataDir());
        dataLabel.setStyle("-fx-text-fill:#6f6f6f; -fx-font-size:10px;");
        storageLabel = new Label("storage: starting…");
        storageLabel.setStyle("-fx-text-fill:#6f6f6f; -fx-font-size:10px;");
        detectionLabel = new Label("AI: waiting for detections");
        detectionLabel.setStyle("-fx-text-fill:#4f4f4f; -fx-font-size:10px;");
        Label hint = detectionLabel;

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(12, configLabel, dataLabel, storageLabel, spacer, hint);
        footer.setPadding(new Insets(8, 14, 10, 14));
        footer.setStyle("-fx-border-color:#222 transparent transparent transparent; -fx-border-width:1 0 0 0;");
        return footer;
    }

    // ---------------------------------------------------------------- storage

    /**
     * Opens the single-writer database, runs the startup reconciliation and schedules the
     * retention sweep (plan 3c). All of it happens off the JavaFX thread.
     */
    /** Newest frame for one camera, used as the intrusion snapshot (engine tap or FFmpeg display cell). */
    private java.awt.image.BufferedImage snapshotForCamera(long cameraId) {
        EngineVideoView engineVideo = engineViews.get(cameraId);
        if (engineVideo != null) {
            return engineVideo.snapshot().orElse(null);
        }
        FfmpegDisplayView view = videoByCamera.get(cameraId);
        return view == null ? null : view.snapshotImage().orElse(null);
    }

    private void startStorage() {
        // Create the file and schema before the writer thread opens it: two connections racing the
        // first WAL switch on a brand-new file can hit "database is locked".
        try (var ignored = com.cctv.app.db.Database.open(config.databaseFile())) {
            // schema applied and closed; the writer thread and the sweeps reuse the file
        } catch (java.sql.SQLException e) {
            log.error("cannot open the database at {} - recording will be unavailable",
                    config.databaseFile(), e);
        }
        // Camera management: SQLite is the runtime source of truth; cameras.properties is imported
        // once, when the table is still empty (first run or first upgrade).
        cameraStore = new CameraStore(config.databaseFile());
        int importedCameras = cameraStore.importIfEmpty(config.cameras());
        if (importedCameras > 0) {
            log.info("cameras imported from {}: {}", config.configFile(), importedCameras);
        }
        refreshActiveCameras();
        log.info("camera list: {} configured, {} enabled", cameraStore.all().size(),
                activeCameras.size());
        repository = RecordingRepository.start(config.databaseFile());
        // Recording metadata: the engine writes packets and announces segments; the app owns SQLite.
        // Both handlers only enqueue, so the engine's writer thread is never blocked by the database.
        bus.subscribe(com.cctv.contract.SegmentStarted.class, started -> {
            repository.segmentStarted(started.cameraId(), started.startUtcMs(), started.file());
            // The recorder opens the next segment before the previous one is finalized, so REC must
            // follow the number of open segments, not a boolean toggled by each event.
            openSegments.computeIfAbsent(started.cameraId(), id -> ConcurrentHashMap.newKeySet())
                    .add(started.file());
            segmentStartUtc.put(started.cameraId(), started.startUtcMs());
            log.debug("segment started: camera {} at {} -> {}", started.cameraId(),
                    started.startUtcMs(), started.file());
            Platform.runLater(() -> updateRecordingBadge(started.cameraId()));
        });
        bus.subscribe(com.cctv.contract.ClipFinalized.class, finalized -> {
            repository.segmentFinalized(finalized.cameraId(), finalized.startUtcMs(),
                    finalized.endUtcMs(), finalized.sizeBytes(), finalized.file(), finalized.keep());
            var open = openSegments.get(finalized.cameraId());
            if (open != null) {
                open.remove(finalized.file());
            }
            segmentStartUtc.remove(finalized.cameraId(), finalized.startUtcMs());
            log.info("clip finalized: camera {} {}..{} ({} bytes, keep={})", finalized.cameraId(),
                    finalized.startUtcMs(), finalized.endUtcMs(), finalized.sizeBytes(), finalized.keep());
            Platform.runLater(() -> {
                updateRecordingBadge(finalized.cameraId());
                showStorageState();
            });
        });
        // Phase 4a/4b: detections inside a segment become clip_labels rows and a JSON sidecar.
        clipLabeler = new com.cctv.app.label.ClipLabeler(bus, repository,
                !"false".equals(System.getProperty("cctv.sidecars")));
        zoneStore = new ZoneStore(config.databaseFile(), repository);
        // Phase 7b/7c: counting lines + crossings. The engine gets this as a LineSource via the
        // pipeline handoff; the app owns persistence, summaries and UI.
        countLines = new CountLineStore(config.databaseFile(), repository);
        countService = new CountService(config.databaseFile(), repository, bus);
        Platform.runLater(() -> {
            countsView = new CountsView(countLines, countService, cameras());
            lineEditor = new LineEditor(countLines, () -> { });
            lineEditor.setOnSaved(saved -> refreshOverlays());
            cameraManager = new CameraManagerView(cameraStore, this::statusTextFor,
                    message -> {
                        log.info("camera list changed: {}", message);
                        rebuildWall();
                    });
            cameraManager.hide();
            wallStack.getChildren().addAll(countsView, lineEditor, cameraManager);
        });
        // Phase 5d: every IntrusionEvent becomes a snapshot PNG + alerts row here; the popup is
        // only the last step, so a slow disk never delays the engine's alert path.
        alertService = new AlertService(config.snapshotsDir(), repository, bus,
                id -> segmentStartUtc.getOrDefault(id, 0L));
        alertService.onPresented(presented -> {
            AlertPopup popup = alertPopup;
            if (popup == null) {
                return;
            }
            Platform.runLater(() -> popup.show(presented.alert(), presented.zoneName(),
                    cameraName(presented.alert().cameraId())));
        });
        // Engine-owned since ChatGPT's ZoneIntrusionDetector: the engine already applies the feet
        // point, two-consecutive and one-event-per-entry rules, so the app-side confirmer is a
        // transitional fallback and is off unless asked for explicitly (never both at once).
        if ("true".equalsIgnoreCase(System.getProperty("cctv.zone.confirm.app"))) {
            zoneConfirmer = new com.cctv.app.alert.ZoneConfirmer(zoneStore::forCamera,
                    this::snapshotForCamera,
                    id -> segmentStartUtc.getOrDefault(id, 0L),
                    bus, null);
            log.info("transitional app-side zone confirmer active (engine owns zones; "
                    + "unset -Dcctv.zone.confirm.app to use the engine path)");
        }
        if (System.getProperty("cctv.zone.seed.demo") != null) {
            seedDemoZone();
        }
        seedDemoLine();
        retention = new RetentionService(config.databaseFile(), config.recordingsDir(),
                RetentionService.Policy.fromConfig(config), System::currentTimeMillis,
                () -> FreeSpace.usableBytes(config.recordingsDir()));
        storageScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "retention");
            thread.setDaemon(true);
            return thread;
        });
        storageScheduler.execute(() -> {
            var report = retention.reconcileStartup();
            log.info("storage ready: {} rows finalized, {} dropped, {} orphan files removed; db {}",
                    report.rowsFinalized(), report.rowsDropped(), report.orphanFilesDeleted(),
                    config.databaseFile());
            Platform.runLater(this::showStorageState);
        });
        storageScheduler.scheduleWithFixedDelay(() -> {
            try {
                retention.runOnce();
                Platform.runLater(this::showStorageState);
            } catch (RuntimeException e) {
                log.error("retention sweep failed", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void updateRecordingBadge(long cameraId) {
        CameraCell cell = cells.get(cameraId);
        if (cell == null) {
            return;
        }
        var open = openSegments.get(cameraId);
        cell.setRecording(open != null && !open.isEmpty());
    }

    private void showStorageState() {
        if (storageLabel == null || storageScheduler == null || storageScheduler.isShutdown()) {
            return;
        }
        if (!storageRefreshPending.compareAndSet(false, true)) {
            return;
        }
        try {
            storageScheduler.execute(() -> {
                StorageSnapshot snapshot = null;
                try {
                    snapshot = readStorageSnapshot();
                } catch (RuntimeException failure) {
                    log.warn("cannot refresh storage status: {}", failure.toString());
                }
                StorageSnapshot ready = snapshot;
                try {
                    Platform.runLater(() -> {
                        try {
                            if (ready != null) {
                                applyStorageSnapshot(ready);
                            }
                        } finally {
                            storageRefreshPending.set(false);
                        }
                    });
                } catch (IllegalStateException toolkitStopped) {
                    storageRefreshPending.set(false);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            storageRefreshPending.set(false);
        }
    }

    /** Reads disk and SQLite state on the storage worker, never on the JavaFX thread. */
    private StorageSnapshot readStorageSnapshot() {
        long free = FreeSpace.usableBytes(config.recordingsDir());
        boolean allowed = retention.recordingAllowed();
        long used = 0;
        boolean dbFailed = repository != null && repository.failure().isPresent();
        try (var connection = com.cctv.app.db.Database.open(config.databaseFile());
             var ps = connection.prepareStatement("SELECT COALESCE(SUM(size_bytes),0) FROM recordings WHERE status='ready'");
             var rs = ps.executeQuery()) {
            if (rs.next()) {
                used = rs.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            dbFailed = true;
            log.warn("cannot read stored bytes: {}", e.toString());
        }
        long dbDropped = repository == null ? 0 : repository.droppedCommandCount();
        int dbQueued = repository == null ? 0 : repository.queueDepth();
        return new StorageSnapshot(used, free, allowed, dbDropped, dbQueued, dbFailed);
    }

    /** Applies a previously read snapshot on the JavaFX thread. */
    private void applyStorageSnapshot(StorageSnapshot snapshot) {
        if (storageLabel == null) {
            return;
        }
        String dbState = snapshot.databaseFailed() ? " · DB ERROR"
                : snapshot.droppedCommands() > 0 ? " · DB WARN (" + snapshot.droppedCommands() + " dropped)"
                : snapshot.queuedCommands() > 250 ? " · DB BUSY (" + snapshot.queuedCommands() + " queued)"
                : " · DB OK";
        storageLabel.setText(String.format("storage: %s clips · %.1f GB free%s%s",
                bytesText(snapshot.usedBytes()), snapshot.freeBytes() / 1_073_741_824.0,
                snapshot.recordingAllowed() ? "" : " · PAUSED (low disk)", dbState));
        boolean healthy = snapshot.recordingAllowed() && !snapshot.databaseFailed()
                && snapshot.droppedCommands() == 0 && snapshot.queuedCommands() <= 250;
        storageLabel.setStyle("-fx-font-size:10px; -fx-text-fill:"
                + (healthy ? "#6f6f6f" : "#ff9d6b") + ";");
    }

    private static String bytesText(long bytes) {
        if (bytes < 1_048_576L) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0);
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1_073_741_824.0);
    }

    // ------------------------------------------------------------------ alerts

    /**
     * Development switch {@code -Dcctv.alert.demo=1}: publishes one synthetic intrusion after a
     * short delay, so the snapshot file, the alerts row and the popup can be checked (and captured
     * for evidence) while the engine's zone logic is still being written.
     */
    private void startAlertDemo() {
        after(4, () -> {
            long cameraId = demoCameraId();
            ZoneDefinition zone = new ZoneDefinition(1, cameraId, "Front gate",
                    0.55, 0.35, 0.30, 0.45, true);
            IntrusionEvent event = new IntrusionEvent(cameraId, zone, System.currentTimeMillis(), 0L,
                    demoSnapshot());
            bus.publish(event);
            log.info("demo intrusion published: camera {} zone {} ({})", cameraId, zone.id(),
                    zone.name());
        });
    }

    private void after(double seconds, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(event -> action.run());
        pause.play();
    }

    private long demoCameraId() {
        for (var camera : cameras()) {
            if (!camera.hasMain()) {
                return camera.id();
            }
        }
        return cameras().isEmpty() ? 1 : cameras().get(0).id();
    }

    private String cameraName(long cameraId) {
        for (var camera : cameras()) {
            if (camera.id() == cameraId) {
                return camera.name();
            }
        }
        return "camera " + cameraId;
    }

    /** A synthetic detection frame with a red box, so demo alerts look like a real snapshot. */
    private static BufferedImage demoSnapshot() {
        BufferedImage image = new BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0x1d, 0x26, 0x30), 960, 540,
                new Color(0x07, 0x08, 0x0a)));
        g.fillRect(0, 0, 960, 540);
        int x = (int) (0.55 * 960);
        int y = (int) (0.35 * 540);
        int w = (int) (0.30 * 960);
        int h = (int) (0.45 * 540);
        g.setColor(new Color(0xff, 0x6a, 0x4d));
        g.setStroke(new BasicStroke(5));
        g.drawRect(x, y, w, h);
        g.setFont(new Font("Segoe UI", Font.BOLD, 30));
        g.drawString("person 0.87", x + 12, y - 14);
        g.setFont(new Font("Segoe UI", Font.PLAIN, 20));
        g.drawString("demo frame - engine not connected", 24, 510);
        g.dispose();
        return image;
    }

    // ------------------------------------------------------------------ engine

    /**
     * Headless switch for evidence runs: writes one zone over the feet area of a person standing
     * in front of the camera, without opening the editor. The engine's zone detector decides the
     * rest, so the run proves the whole engine → bus → alert path.
     */
    private void seedDemoZone() {
        CameraConfig engineCamera = null;
        for (var candidate : cameras()) {
            if (candidate.hasMain() && (candidate.ai() || candidate.record())) {
                engineCamera = candidate;
                break;
            }
        }
        if (engineCamera == null) {
            log.warn("zone seed skipped: no camera runs the engine");
            return;
        }
        long cameraId = engineCamera.id();
        String name = System.getProperty("cctv.zone.seed.name", "Engine zone");
        double[] rect = doubles(System.getProperty("cctv.zone.seed.rect", "0.04,0.55,0.46,0.40"), 4);
        if (rect == null) {
            log.warn("zone seed skipped: cctv.zone.seed.rect must be x,y,w,h");
            return;
        }
        zoneStore.replaceForCamera(cameraId, java.util.List.of(
                new com.cctv.app.db.ZoneDraft(name, rect[0], rect[1], rect[2], rect[3], true)));
        log.info("zone seed queued: camera {} -> {} ({},{},{},{})", cameraId, name, rect[0], rect[1],
                rect[2], rect[3]);
    }

    /**
     * Headless counting-line seed for acceptance runs: the frozen-frame editor is not available in
     * a scripted session, so the line comes from properties instead.
     */
    private void seedDemoLine() {
        String raw = System.getProperty("cctv.line.seed.points");
        if (raw == null) {
            return;
        }
        CameraConfig engineCamera = null;
        for (var candidate : cameras()) {
            if (candidate.hasMain() && (candidate.ai() || candidate.record())) {
                engineCamera = candidate;
                break;
            }
        }
        if (engineCamera == null) {
            log.warn("line seed skipped: no camera runs the engine");
            return;
        }
        double[] p = doubles(raw, 4);
        if (p == null) {
            log.warn("line seed skipped: cctv.line.seed.points must be x1,y1,x2,y2");
            return;
        }
        String name = System.getProperty("cctv.line.seed.name", "Walk line");
        countLines.replaceForCamera(engineCamera.id(), java.util.List.of(
                new com.cctv.app.db.LineDraft(name, p[0], p[1], p[2], p[3], true)));
        log.info("line seed queued: camera {} -> {} ({},{})-({},{})", engineCamera.id(), name, p[0],
                p[1], p[2], p[3]);
    }

    private static double[] doubles(String csv, int expected) {
        String[] parts = csv.split(",");
        if (parts.length != expected) {
            return null;
        }
        double[] values = new double[expected];
        try {
            for (int i = 0; i < expected; i++) {
                values[i] = Double.parseDouble(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return values;
    }

    /**
     * Starts an independent capture/record/AI runtime for every enabled processing camera.
     * Model loading stays off the JavaFX thread; one camera's failure does not block the others.
     */
    private void startEngine() {
        List<CameraConfig> targets = cameras().stream()
                .filter(camera -> camera.hasMain() && camera.needsMainStream())
                .toList();
        if (targets.isEmpty()) {
            log.info("camera runtimes not started: no camera needs a main stream");
            return;
        }
        final long generation = engineGeneration.get();
        final Path model;
        try {
            model = com.cctv.ai.ModelPathResolver.resolve(System.getProperty("cctv.model"),
                    Path.of(CamWatchmanApp.class.getProtectionDomain().getCodeSource()
                            .getLocation().toURI()),
                    Path.of(System.getProperty("user.dir")));
        } catch (Exception resolutionFailure) {
            log.error("could not resolve bundled AI model: {}", resolutionFailure.toString());
            for (CameraConfig target : targets) {
                bus.publish(StreamStatusEvent.now(target.id(), StreamState.ERROR,
                        "AI model unavailable: " + resolutionFailure.getClass().getSimpleName()));
            }
            return;
        }
        if (engineStart == null || engineStart.isShutdown()) {
            engineStart = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "camera-runtime-start");
                thread.setDaemon(true);
                return thread;
            });
        }
        engineStart.submit(() -> {
            for (CameraConfig target : targets) {
                if (engineGeneration.get() != generation || Thread.currentThread().isInterrupted()) {
                    return;
                }
                EngineHandle created = null;
                try {
                    created = EngineHandle.create(target.id(), target.mainUrl(), model,
                            config.recordingsDir(), config.segmentSeconds(), target.record(), target.ai(), bus,
                            config.aiRateFps(), countLines, zoneStore);
                    created.start();
                    synchronized (runtimeLock) {
                        if (engineGeneration.get() != generation || Thread.currentThread().isInterrupted()) {
                            created.close();
                            return;
                        }
                        EngineHandle replaced = pipelines.put(target.id(), created);
                        if (replaced != null) {
                            replaced.close();
                        }
                    }
                    log.info("camera runtime started: camera {} '{}' (record={}, ai={}, {} fps)",
                            target.id(), target.name(), target.record(), target.ai(), config.aiRateFps());
                    refreshOverlays();
                } catch (Exception failure) {
                    if (created != null) {
                        created.close();
                    }
                    log.error("camera runtime failed for camera {}: {}", target.id(), failure.toString());
                    bus.publish(StreamStatusEvent.now(target.id(), StreamState.ERROR,
                            "camera runtime start failed: " + failure.getClass().getSimpleName()));
                }
            }
        });
    }

    /** Refreshes zones and counting lines on each processing camera's own overlay. */
    private void refreshOverlays() {
        Runnable task = () -> detectionOverlays.forEach((cameraId, overlay) -> {
            if (zoneStore != null) {
                overlay.setZones(zoneStore.activeZones(cameraId));
            }
            if (countLines != null) {
                overlay.setLines(countLines.activeLines(cameraId));
            }
        });
        if (javafx.application.Platform.isFxApplicationThread()) {
            task.run();
        } else {
            javafx.application.Platform.runLater(task);
        }
    }

    /** Footer hint: newest detection per class, as the engine publishes them. */
    private void refreshDetectionHint() {        if (detectionLabel == null) {
            return;
        }
        if (lastDetectionByClass.isEmpty()) {
            detectionLabel.setText("AI: waiting for detections");
            return;
        }
        StringBuilder text = new StringBuilder("AI: ").append(detectionsSeen.get()).append(" detection(s)");
        lastDetectionByClass.values().stream()
                .sorted(Comparator.comparing(Detection::objectClass))
                .forEach(d -> text.append("  ").append(d.objectClass())
                        .append(String.format(Locale.ROOT, " %.2f", d.confidence())));
        detectionLabel.setText(text.toString());
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void stop() {
        synchronized (runtimeLock) {
            engineGeneration.incrementAndGet();
            pipelines.values().forEach(EngineHandle::close);
            pipelines.clear();
        }
        log.info("camera runtimes closed");
        if (engineStart != null) {
            engineStart.shutdownNow();
        }
        engineViews.values().forEach(EngineVideoView::close);
        engineViews.clear();
        detectionOverlays.values().forEach(DetectionOverlay::close);
        detectionOverlays.clear();
        for (FfmpegDisplayView video : videoViews) {
            video.close();
        }
        videoViews.clear();
        if (storageScheduler != null) {
            storageScheduler.shutdownNow();
        }
        if (trackingSubscription != null) {
            trackingSubscription.close();
            trackingSubscription = null;
        }
        if (zoneConfirmer != null) {
            zoneConfirmer.close();
        }
        if (countsView != null) {
            countsView.hide();
            countsView.close();
        }
        if (cameraManager != null) {
            cameraManager.close();
        }
        if (countService != null) {
            countService.close();
            countService = null;
        }
        if (countLines != null) {
            countLines.close();
            countLines = null;
        }
        if (alertService != null) {
            alertService.close();
        }
        if (clipLabeler != null) {
            clipLabeler.close();
            clipLabeler = null;
        }
        if (repository != null) {
            repository.close();
        }
        log.info("storage closed");
        reportSurvivingThreads();
    }

    /** Phase 8a: a clean exit must not leave non-daemon threads (they would keep the JVM alive). */
    private void reportSurvivingThreads() {
        // The launcher threads of the JVM itself are expected to be alive while stop() runs.
        Set<String> expected = Set.of("main", "JavaFX-Launcher", "JavaFX Application Thread",
                "DestroyJavaVM", "Reference Handler", "Finalizer", "Signal Dispatcher");
        List<String> survivors = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t != Thread.currentThread() && !t.isDaemon() && t.isAlive())
                .map(Thread::getName)
                .filter(name -> !expected.contains(name))
                .sorted()
                .toList();
        if (survivors.isEmpty()) {
            log.info("shutdown audit: no non-daemon threads left");
        } else {
            log.warn("shutdown audit: non-daemon threads still alive: {}", survivors);
        }
    }

    private void publishInitialStates() {
        // Until the engine exists, every camera reports OFFLINE with an honest reason.
        for (var camera : cameras()) {
            bus.publish(StreamStatusEvent.now(camera.id(), StreamState.OFFLINE,
                    "waiting for camera runtime"));
        }
    }

    // ------------------------------------------------------- config problems

    private void showConfigError(Path configPath, ConfigException error) {
        Label title = new Label("Configuration problem");
        title.setStyle("-fx-text-fill:#ff6b6b; -fx-font-size:16px; -fx-font-weight:bold;");

        Label where = new Label("file: " + configPath);
        where.setStyle("-fx-text-fill:#9a9a9a; -fx-font-size:11px;");

        VBox problems = new VBox(4);
        for (String problem : error.problems()) {
            Label line = new Label("• " + problem);
            line.setStyle("-fx-text-fill:#e0e0e0; -fx-font-size:12px;");
            line.setWrapText(true);
            problems.getChildren().add(line);
        }

        Button writeRig = new Button("Write test-rig cameras.properties here and retry");
        writeRig.setOnAction(e -> {
            try {
                RigConfigTemplate.write(configPath);
                log.info("wrote test-rig configuration to {}", configPath);
            } catch (IOException io) {
                log.error("could not write {}", configPath, io);
            }
            restartSelf();
        });

        Button quit = new Button("Quit");
        quit.setOnAction(e -> Platform.exit());

        HBox buttons = new HBox(8, writeRig, quit);
        VBox box = new VBox(10, title, where, problems, buttons);
        box.setPadding(new Insets(24));
        box.setStyle("-fx-background-color:#121212;");

        Scene scene = new Scene(box, 760, 420);
        stage.setTitle("Cam Watchman — configuration");
        stage.setScene(scene);
        stage.show();
    }

    private void restartSelf() {
        try {
            final String java = ProcessHandle.current().info().command().orElse("java");
            final String classPath = System.getProperty("java.class.path");
            new ProcessBuilder(java, "-cp", classPath, Main.class.getName())
                    .inheritIO()
                    .start();
        } catch (IOException e) {
            log.error("could not restart after writing config", e);
        }
        Platform.exit();
    }

    // ----------------------------------------------------------- utilities

    private static Path resolveConfigPath() {
        String explicit = System.getProperty("cctv.config");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("cameras.properties").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd)) {
            return cwd;
        }
        return ConfigLoader.defaultBaseDataDir().resolve("cameras.properties");
    }

    /** {@code -Dcctv.snapshot=<png>} renders one frame to disk and exits — used for evidence runs. */
    private void snapshotIfRequested(Scene scene) {
        String target = System.getProperty("cctv.snapshot");
        if (target == null || target.isBlank()) {
            return;
        }
        double seconds = Double.parseDouble(System.getProperty("cctv.snapshot.delay", "1.5"));
        PauseTransition pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(e -> {
            try {
                WritableImage image = scene.snapshot(null);
                Path out = Path.of(target).toAbsolutePath();
                writePng(image, out);
                log.info("snapshot written to {}", out);
            } catch (IOException | RuntimeException ex) {
                log.error("snapshot failed", ex);
            } finally {
                Platform.exit();
            }
        });
        pause.play();
    }

    private static void writePng(WritableImage image, Path out) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        javax.imageio.ImageIO.write(buffered, "png", out.toFile());
    }

}
