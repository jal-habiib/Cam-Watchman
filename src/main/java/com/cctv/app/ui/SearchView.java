package com.cctv.app.ui;

import com.cctv.app.db.SearchService;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 4c: search kept clips by object text and date range (plan P4c). Purely a reader on the
 * SQLite tables the engine fills at clip finalize; rows whose file vanished are hidden by
 * {@link SearchService} so a result can always be played.
 */
public final class SearchView extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(SearchView.class);
    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT);

    private final Path databaseFile;
    private final ZoneId zone = ZoneId.systemDefault();
    private final TextField objectField = new TextField();
    private final DatePicker fromPicker = new DatePicker(LocalDate.now().minusDays(7));
    private final DatePicker toPicker = new DatePicker(LocalDate.now());
    private final TableView<SearchService.Hit> table = new TableView<>();
    private final ObservableList<SearchService.Hit> rows = FXCollections.observableArrayList();
    private final Label status = new Label("Search keeps only kept clips (trigger = detection).");

    public SearchView(Path databaseFile, Runnable onBack) {
        this.databaseFile = databaseFile;
        setPadding(new Insets(12));
        setStyle("-fx-background-color:#0d0d0d;");
        setTop(form(onBack));
        setCenter(table());
        setBottom(footer());
    }

    private HBox form(Runnable onBack) {
        Button back = new Button("← Wall");
        back.setOnAction(event -> onBack.run());
        back.setStyle(buttonStyle());

        objectField.setPromptText("object (e.g. car, person, truck)");
        objectField.setPrefColumnCount(18);
        objectField.setStyle(fieldStyle());
        objectField.setOnAction(event -> runSearch());

        fromPicker.setPrefWidth(130);
        toPicker.setPrefWidth(130);
        fromPicker.setStyle(fieldStyle());
        toPicker.setStyle(fieldStyle());

        Button search = new Button("Search");
        search.setOnAction(event -> runSearch());
        search.setStyle(buttonStyle() + "-fx-background-color:#1f4e79;");

        Label objectLabel = new Label("Object");
        objectLabel.setStyle(labelStyle());
        Label fromLabel = new Label("From");
        fromLabel.setStyle(labelStyle());
        Label toLabel = new Label("To");
        toLabel.setStyle(labelStyle());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label hint = new Label("Enter in the object box also searches");
        hint.setStyle("-fx-text-fill:#5a5a5a; -fx-font-size:10px;");

        HBox bar = new HBox(8, back, objectLabel, objectField, fromLabel, fromPicker, toLabel, toPicker,
                search, spacer, hint);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 10, 0));
        return bar;
    }

    private TableView<SearchService.Hit> table() {
        table.setItems(rows);
        table.setStyle("-fx-background-color:#0f0f0f; -fx-control-inner-background:#101010;"
                + " -fx-table-cell-border-color:#222;");

        TableColumn<SearchService.Hit, String> when = new TableColumn<>("When (local)");
        when.setCellValueFactory(cell -> new SimpleStringProperty(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(cell.getValue().startUtcMs()), zone)
                        .format(LOCAL)));
        when.setPrefWidth(190);

        TableColumn<SearchService.Hit, String> camera = new TableColumn<>("Camera");
        camera.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().cameraId())));
        camera.setPrefWidth(80);

        TableColumn<SearchService.Hit, String> length = new TableColumn<>("Length");
        length.setCellValueFactory(cell -> new SimpleStringProperty(
                String.format("%.0f s", cell.getValue().durationMs() / 1000.0)));
        length.setPrefWidth(80);

        TableColumn<SearchService.Hit, String> objects = new TableColumn<>("Objects");
        objects.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().objects()));
        objects.setPrefWidth(300);

        TableColumn<SearchService.Hit, String> size = new TableColumn<>("Size");
        size.setCellValueFactory(cell -> new SimpleStringProperty(
                String.format("%.1f MB", cell.getValue().sizeBytes() / 1_048_576.0)));
        size.setPrefWidth(90);

        TableColumn<SearchService.Hit, String> file = new TableColumn<>("File");
        file.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().file().getFileName().toString()));
        file.setPrefWidth(220);

        TableColumn<SearchService.Hit, Void> action = new TableColumn<>("Playback");
        action.setPrefWidth(92);
        action.setMinWidth(92);
        action.setSortable(false);
        action.setCellFactory(column -> new TableCell<>() {
            private final Button play = new Button("Play ▶");
            {
                play.setStyle(buttonStyle() + "-fx-background-color:#174b35;");
                play.setOnAction(event -> {
                    SearchService.Hit hit = getTableRow().getItem();
                    if (hit != null) {
                        play(hit);
                    }
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : play);
            }
        });

        table.getColumns().addAll(when, camera, length, objects, size, file, action);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Label empty = new Label("No clips match these filters. Try widening the date range or clearing the object filter.");
        empty.setStyle("-fx-text-fill:#777; -fx-font-size:12px;");
        table.setPlaceholder(empty);
        table.setRowFactory(view -> {
            javafx.scene.control.TableRow<SearchService.Hit> row =
                    new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    play(row.getItem());
                }
            });
            return row;
        });
        return table;
    }

    private VBox footer() {
        status.setStyle("-fx-text-fill:#7a7a7a; -fx-font-size:11px;");
        Label hint = new Label("Choose Play on a clip, or double-click its row to open it in your default video player.");
        hint.setStyle("-fx-text-fill:#4f4f4f; -fx-font-size:10px;");
        VBox box = new VBox(2, status, hint);
        box.setPadding(new Insets(8, 0, 0, 0));
        return box;
    }

    /** Builds the UTC range: local start-of-day on "from" to end-of-day on "to". */
    private SearchService.Query query() {
        LocalDate from = fromPicker.getValue() == null ? LocalDate.now().minusDays(7) : fromPicker.getValue();
        LocalDate to = toPicker.getValue() == null ? LocalDate.now() : toPicker.getValue();
        long fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli();
        long toMs = LocalDateTime.of(to, LocalTime.MAX).atZone(zone).toInstant().toEpochMilli();
        return SearchService.Query.of(objectField.getText(), fromMs, toMs);
    }

    /** Runs the SQLite read off the FX thread, then fills the table on it. */
    public void runSearch() {
        LocalDate from = fromPicker.getValue();
        LocalDate to = toPicker.getValue();
        if (from != null && to != null && from.isAfter(to)) {
            status.setText("Invalid date range: the From date must be on or before the To date.");
            return;
        }
        SearchService.Query query = query();
        status.setText("Searching clips…");
        Task<SearchService.Result> task = new Task<>() {
            @Override
            protected SearchService.Result call() throws Exception {
                return SearchService.search(databaseFile, query);
            }
        };
        task.setOnSucceeded(event -> {
            SearchService.Result result = task.getValue();
            rows.setAll(result.hits());
            String objectText = query.objectText().isBlank() ? "all objects" : "'" + query.objectText() + "'";
            status.setText(String.format("%d clip(s) for %s from %s to %s%s", result.hits().size(),
                    objectText, fromPicker.getValue(), toPicker.getValue(),
                    result.skippedMissingFiles() == 0 ? ""
                            : " · " + result.skippedMissingFiles() + " row(s) skipped (file missing)"));
        });
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            log.warn("clip search failed", error);
            status.setText("search failed: " + (error == null ? "unknown" : error.getMessage()));
        });
        Thread thread = new Thread(task, "clip-search");
        thread.setDaemon(true);
        thread.start();
    }

    public void setObjectText(String text) {
        objectField.setText(text);
    }

    private void play(SearchService.Hit hit) {
        // A retained clip can expire after the search query has populated the table.
        // Recheck immediately before opening and refresh instead of leaving a dead result behind.
        if (!Files.isRegularFile(hit.file())) {
            status.setText("This clip is no longer available. Refreshing results…");
            runSearch();
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(hit.file().toFile());
                log.info("opened clip {} in the desktop player", hit.file());
            } else {
                log.info("no desktop player; clip is at {}", hit.file());
                status.setText("No desktop video player is available. Clip: " + hit.file());
            }
        } catch (Exception e) {
            log.warn("could not open {}: {}", hit.file(), e.toString());
            if (!Files.isRegularFile(hit.file())) {
                status.setText("This clip is no longer available. Refreshing results…");
                runSearch();
            } else {
                status.setText("open failed: " + e.getMessage());
            }
        }
    }

    private static String buttonStyle() {
        return "-fx-background-color:#1b1b1b; -fx-text-fill:#e0e0e0; -fx-border-color:#333;"
                + " -fx-border-radius:4; -fx-background-radius:4; -fx-font-size:11px;";
    }

    private static String fieldStyle() {
        return "-fx-background-color:#151515; -fx-text-fill:#e0e0e0; -fx-prompt-text-fill:#666;"
                + " -fx-border-color:#333; -fx-border-radius:4; -fx-background-radius:4;";
    }

    private static String labelStyle() {
        return "-fx-text-fill:#8a8a8a; -fx-font-size:11px;";
    }
}
