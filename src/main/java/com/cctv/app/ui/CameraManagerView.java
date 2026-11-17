package com.cctv.app.ui;

import com.cctv.app.db.CameraStore;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * "Manage cameras" overlay (owner requirement): add, edit, enable/disable and remove cameras
 * without editing files, with the stream status visible next to every camera.
 *
 * <p>Removal is soft (history preserved) and runs through {@link CameraStore}; the wall rebuilds
 * via the {@code onChanged} callback so tiles, subscriptions and engine workers follow the list.
 */
public final class CameraManagerView extends BorderPane {

    private static final Logger log = LoggerFactory.getLogger(CameraManagerView.class);

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "camera-manager");
        thread.setDaemon(true);
        return thread;
    });

    private final CameraStore store;
    private final Function<Long, String> statusText;
    private final Consumer<String> onChanged;
    private final TableView<CameraStore.Camera> table = new TableView<>();
    private final Label status = new Label();
    private final Button editButton = new Button("Edit");
    private final Button toggleButton = new Button("Disable");
    private final Button removeButton = new Button("Remove");

    public CameraManagerView(CameraStore store, Function<Long, String> statusText,
            Consumer<String> onChanged) {
        this.store = store;
        this.statusText = statusText;
        this.onChanged = onChanged;

        getStyleClass().add("overlay-panel");
        setBackground(new javafx.scene.layout.Background(
                new javafx.scene.layout.BackgroundFill(javafx.scene.paint.Color.web("#0f1317"),
                        null, null)));
        setPadding(new Insets(18));

        Label title = new Label("CAMERAS");
        title.setStyle("-fx-text-fill:#f2f2f2; -fx-font-size:15px; -fx-font-weight:bold;");
        Label hint = new Label("add, edit, disable or remove cameras - history of removed cameras is kept");
        hint.setStyle("-fx-text-fill:#8a949e; -fx-font-size:11px;");
        VBox titles = new VBox(2, title, hint);

        Button addButton = new Button("+ Add camera");
        Button closeButton = new Button("Close");
        addButton.setOnAction(event -> openForm(null));
        closeButton.setOnAction(event -> hide());
        HBox actions = new HBox(8, addButton, editButton, toggleButton, removeButton, closeButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, titles, spacer, actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 12, 0));

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setStyle("-fx-base:#1c2127; -fx-control-inner-background:#161a1f;");
        TableColumn<CameraStore.Camera, String> name = column("Camera", 150,
                camera -> camera.name());
        TableColumn<CameraStore.Camera, String> state = column("Status", 110,
                camera -> statusText.apply(camera.id()));
        TableColumn<CameraStore.Camera, String> record = column("Record", 70,
                camera -> camera.record() ? "yes" : "no");
        TableColumn<CameraStore.Camera, String> ai = column("AI", 60,
                camera -> camera.ai() ? "yes" : "no");
        TableColumn<CameraStore.Camera, String> enabled = column("Enabled", 80,
                camera -> camera.enabled() ? "yes" : "no");
        TableColumn<CameraStore.Camera, String> source = column("Source (credentials hidden)", 300,
                CameraStore.Camera::displayUrl);
        table.getColumns().addAll(List.of(name, state, record, ai, enabled, source));
        table.setPlaceholder(new Label("No cameras yet - use \"+ Add camera\"."));
        VBox.setVgrow(table, Priority.ALWAYS);

        status.setStyle("-fx-text-fill:#c9d1d9; -fx-font-size:11px;");

        editButton.setOnAction(event -> openForm(selected()));
        toggleButton.setOnAction(event -> toggleSelected());
        removeButton.setOnAction(event -> removeSelected());
        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openForm(selected());
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            boolean none = value == null;
            editButton.setDisable(none);
            removeButton.setDisable(none);
            toggleButton.setDisable(none);
            toggleButton.setText(value != null && value.enabled() ? "Disable" : "Enable");
        });
        editButton.setDisable(true);
        removeButton.setDisable(true);
        toggleButton.setDisable(true);

        setTop(header);
        setCenter(table);
        setBottom(status);
        BorderPane.setMargin(status, new Insets(10, 0, 0, 0));
        refresh();
    }

    private static TableColumn<CameraStore.Camera, String> column(String title, double width,
            Function<CameraStore.Camera, String> value) {
        TableColumn<CameraStore.Camera, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
        return column;
    }

    /** Stops the manager's background worker; called on application shutdown. */
    public void close() {
        WORKER.shutdownNow();
    }

    public void show() {
        setVisible(true);
        setManaged(true);
        refresh();
    }

    public void hide() {
        setVisible(false);
        setManaged(false);
    }

    public boolean isOpen() {
        return isVisible();
    }

    public void refresh() {
        WORKER.execute(() -> {
            List<CameraStore.Camera> cameras = store.all();
            Platform.runLater(() -> {
                CameraStore.Camera previous = selected();
                table.setItems(FXCollections.observableArrayList(cameras));
                if (previous != null) {
                    cameras.stream().filter(camera -> camera.id() == previous.id()).findFirst()
                            .ifPresent(camera -> table.getSelectionModel().select(camera));
                }
                status.setText(cameras.size() + " camera(s) configured - "
                        + cameras.stream().filter(CameraStore.Camera::enabled).count()
                        + " enabled");
            });
        });
    }

    private CameraStore.Camera selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    private void openForm(CameraStore.Camera camera) {
        Window owner = getScene() == null ? null : getScene().getWindow();
        CameraForm.open(owner, store, camera, message -> {
            log.info(message);
            refresh();
            if (onChanged != null) {
                onChanged.accept(message);
            }
        });
    }

    private void toggleSelected() {
        CameraStore.Camera camera = selected();
        if (camera == null) {
            return;
        }
        WORKER.execute(() -> {
            store.setEnabled(camera.id(), !camera.enabled());
            Platform.runLater(() -> {
                log.info("camera '{}' {}", camera.name(), camera.enabled() ? "disabled" : "enabled");
                refresh();
                if (onChanged != null) {
                    onChanged.accept("camera '" + camera.name() + "' "
                            + (camera.enabled() ? "disabled" : "enabled"));
                }
            });
        });
    }

    private void removeSelected() {
        CameraStore.Camera camera = selected();
        if (camera == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(getScene() == null ? null : getScene().getWindow());
        confirm.setTitle("Remove camera");
        confirm.setHeaderText("Remove '" + camera.name() + "'?");
        confirm.setContentText("The camera stops recording and disappears from the wall. Existing"
                + " recordings, alerts and counts are kept in the database.");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }
        WORKER.execute(() -> {
            store.remove(camera.id());
            Platform.runLater(() -> {
                log.info("camera '{}' removed (history kept)", camera.name());
                refresh();
                if (onChanged != null) {
                    onChanged.accept("camera '" + camera.name() + "' removed");
                }
            });
        });
    }
}
