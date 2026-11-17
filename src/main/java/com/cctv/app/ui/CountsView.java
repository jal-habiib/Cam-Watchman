package com.cctv.app.ui;

import com.cctv.app.count.CountLineStore;
import com.cctv.app.count.CountService;
import com.cctv.app.config.CameraConfig;
import com.cctv.contract.CountLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.application.Platform;
import javafx.util.Duration;
import org.slf4j.Logger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/**
 * Phase 7c: today's people counts per counting line, with a manual reset and a live refresh every
 * two seconds while visible. Counts come from the daily {@code count_summaries} table folded by
 * {@link CountService}; line names come from {@link CountLineStore}.
 */
public final class CountsView extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(CountsView.class);

    /** SQLite reads and resets run here so the JavaFX thread never blocks on the database. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "counts-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshBusy = new AtomicBoolean();

    /** One table row: camera, line, today's IN/OUT/total. */
    public record Row(String camera, String line, long countIn, long countOut) {

        public long total() {
            return countIn + countOut;
        }
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final CountLineStore lines;
    private final CountService counts;
    private final List<CameraConfig> cameras;
    private final Label status = new Label();
    private final Timeline refresher;

    public CountsView(CountLineStore lines, CountService counts, List<CameraConfig> cameras) {
        this.lines = lines;
        this.counts = counts;
        this.cameras = cameras;

        TableView<Row> table = new TableView<>(rows);
        table.setStyle("-fx-background-color:#0f1317; -fx-control-inner-background:#12161b;");
        TableColumn<Row, String> cameraColumn = new TableColumn<>("Camera");
        cameraColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().camera()));
        cameraColumn.setPrefWidth(180);
        TableColumn<Row, String> lineColumn = new TableColumn<>("Line");
        lineColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().line()));
        lineColumn.setPrefWidth(260);
        TableColumn<Row, Number> inColumn = new TableColumn<>("IN");
        inColumn.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().countIn()));
        inColumn.setPrefWidth(90);
        TableColumn<Row, Number> outColumn = new TableColumn<>("OUT");
        outColumn.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().countOut()));
        outColumn.setPrefWidth(90);
        TableColumn<Row, Number> totalColumn = new TableColumn<>("Total");
        totalColumn.setCellValueFactory(c -> new SimpleLongProperty(c.getValue().total()));
        totalColumn.setPrefWidth(90);
        table.getColumns().addAll(List.of(cameraColumn, lineColumn, inColumn, outColumn,
                totalColumn));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        setCenter(table);

        Label title = new Label("COUNTS · " + counts.today());
        title.getStyleClass().add("overlay-title");
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        Button reset = new Button("Reset today");
        reset.setOnAction(e -> {
            reset.setDisable(true);
            List<CameraConfig> snapshot = List.copyOf(cameras);
            worker.execute(() -> {
                for (CameraConfig camera : snapshot) {
                    counts.reset(camera.id(), counts.today());
                }
                Platform.runLater(() -> {
                    status.setText("counters reset for today");
                    reset.setDisable(false);
                    refresh();
                });
            });
        });
        Button close = new Button("Close");
        close.setOnAction(e -> setVisible(false));

        Label hint = new Label("one row per counting line · IN/OUT from LineCrossingEvent");
        hint.getStyleClass().add("hint");
        status.getStyleClass().add("hint");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        HBox bar = new HBox(10, title, spring, hint, refresh, reset, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 14, 10, 14));
        bar.getStyleClass().add("overlay-bar");
        bar.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(
                javafx.scene.paint.Color.web("#141a20"), null, null)));
        title.setStyle("-fx-text-fill:#f2f2f2; -fx-font-size:12px; -fx-font-weight:bold;");
        hint.setStyle("-fx-text-fill:#8a949e; -fx-font-size:11px;");
        setTop(bar);

        HBox footer = new HBox(10, status);
        footer.setPadding(new Insets(8, 14, 10, 14));
        setBottom(footer);
        setPadding(Insets.EMPTY);
        setStyle("-fx-base:#161a1f; -fx-background:#0f1317; -fx-control-inner-background:#12161b; -fx-text-background-color:#e6edf3;");
        getStyleClass().add("overlay-panel");
        setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(
                javafx.scene.paint.Color.web("#0f1317"), null, null)));

        refresher = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            if (isVisible()) {
                refresh();
            }
        }));
        refresher.setCycleCount(Animation.INDEFINITE);
        setVisible(false);
    }

    /** Reloads today's totals off the JavaFX thread; called on open and by the two-second timer. */
    public void refresh() {
        if (!refreshBusy.compareAndSet(false, true)) {
            return;
        }
        List<CameraConfig> snapshot = List.copyOf(cameras);
        worker.execute(() -> {
            List<Row> computed = new ArrayList<>();
            int withLines = 0;
            for (CameraConfig camera : snapshot) {
                try {
                    List<CountLine> cameraLines = lines.forCamera(camera.id());
                    if (!cameraLines.isEmpty()) {
                        withLines++;
                    }
                    Map<Long, CountService.Totals> totals =
                            counts.totalsForCamera(camera.id(), counts.today());
                    for (CountLine line : cameraLines) {
                        CountService.Totals day = totals.get(line.id());
                        computed.add(new Row(camera.name(),
                                line.name() + (line.active() ? "" : " (inactive)"),
                                day == null ? 0 : day.countIn(), day == null ? 0 : day.countOut()));
                    }
                } catch (java.sql.SQLException e) {
                    log.warn("counts: cannot read camera {}: {}", camera.id(), e.toString());
                }
            }
            int linesSeen = withLines;
            long crossings = counts.eventsSeen();
            Platform.runLater(() -> {
                rows.setAll(computed);
                status.setText(computed.isEmpty()
                        ? "no counting lines configured yet"
                        : linesSeen + " camera(s) with lines · " + crossings
                                + " crossing(s) seen");
                refreshBusy.set(false);
            });
        });
    }

    /** Stops the timer and the worker thread; called on application shutdown. */
    public void close() {
        refresher.stop();
        worker.shutdownNow();
    }

    /** Called when the panel becomes visible. */
    public void show() {
        setVisible(true);
        refresh();
        refresher.play();
    }

    public void hide() {
        refresher.stop();
        setVisible(false);
    }
}
