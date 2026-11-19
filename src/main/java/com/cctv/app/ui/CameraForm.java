package com.cctv.app.ui;

import com.cctv.app.db.CameraStore;
import com.cctv.app.net.RtspProbe;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Add/edit camera dialog (camera management requirement).
 *
 * <p>Credentials are typed into separate user/password fields, masked, folded into the RTSP URL
 * only when saving, and never shown again - the table and the wall show the redacted URL. The
 * connection test opens a short-lived grabber, so it is disabled while the camera is live (one
 * connection per stream profile rule).
 */
public final class CameraForm {

    private static final Logger log = LoggerFactory.getLogger(CameraForm.class);
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "camera-form");
        thread.setDaemon(true);
        return thread;
    });

    private final Stage stage;
    private final CameraStore store;
    private final CameraStore.Camera existing;
    private final Consumer<String> onSaved;

    private final TextField nameField = new TextField();
    private final TextField urlField = new TextField();
    private final TextField substreamField = new TextField();
    private final TextField userField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final CheckBox recordBox = new CheckBox("record");
    private final CheckBox aiBox = new CheckBox("AI");
    private final CheckBox enabledBox = new CheckBox("enabled");
    private final Label status = new Label();
    private final Button testButton = new Button("Test connection");
    private final Button saveButton = new Button("Save");

    private CameraForm(Window owner, CameraStore store, CameraStore.Camera existing,
            Consumer<String> onSaved) {
        this.store = store;
        this.existing = existing;
        this.onSaved = onSaved;
        this.stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(existing == null ? "Add camera" : "Edit camera - " + existing.name());

        nameField.setPromptText("e.g. Front door");
        urlField.setPromptText("rtsp://host:554/stream1");
        substreamField.setPromptText("rtsp://host:554/stream2 (optional)");
        userField.setPromptText("user (optional)");
        passwordField.setPromptText("password (optional)");
        status.getStyleClass().add("hint");
        status.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int row = 0;
        Label help = new Label("Paste your camera's RTSP link (from its phone app, web UI or manual), "
                + "e.g. rtsp://user:password@192.168.1.50:554/stream1 - put the username and password "
                + "in their own boxes below.");
        help.setWrapText(true);
        help.setStyle("-fx-text-fill:#8a949e; -fx-font-size:11px;");
        grid.add(help, 0, row++, 2, 1);
        grid.addRow(row++, new Label("Name"), nameField);
        grid.addRow(row++, new Label("Main stream"), urlField);
        grid.addRow(row++, new Label("Substream"), substreamField);
        grid.addRow(row++, new Label("Username"), userField);
        grid.addRow(row++, new Label("Password"), passwordField);
        HBox flags = new HBox(14, recordBox, aiBox, enabledBox);
        grid.add(flags, 1, row++);
        grid.add(status, 0, row, 2, 1);

        testButton.setOnAction(event -> runProbe());
        saveButton.setOnAction(event -> save());
        Button cancel = new Button("Cancel");
        cancel.setOnAction(event -> stage.close());
        HBox buttons = new HBox(8, saveButton, testButton, cancel);
        buttons.setPadding(new Insets(0, 14, 14, 14));

        VBox root = new VBox(6, grid, buttons);
        root.setStyle("-fx-base:#1c2127; -fx-background-color:#0f1317; -fx-control-inner-background:#12161b; -fx-text-background-color:#e6edf3; -fx-accent:#2f81f7;");
        for (javafx.scene.Node node : grid.getChildren()) {
            if (node instanceof Label label) {
                label.setStyle("-fx-text-fill:#c9d1d9;");
            }
        }
        grid.getColumnConstraints().add(new javafx.scene.layout.ColumnConstraints());
        javafx.scene.layout.ColumnConstraints grow = new javafx.scene.layout.ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().add(grow);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(urlField, Priority.ALWAYS);
        GridPane.setHgrow(substreamField, Priority.ALWAYS);

        populate();

        Scene scene = new Scene(root, 560, 380);
        stage.setScene(scene);
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.setOnShown(event -> nameField.requestFocus());
    }

    /** Opens the dialog; returns immediately (non-blocking). */
    public static void open(Window owner, CameraStore store, CameraStore.Camera existing,
            Consumer<String> onSaved) {
        CameraForm form = new CameraForm(owner, store, existing, onSaved);
        form.stage.show();
        form.captureForEvidence();
    }

    /**
     * Headless acceptance hook: the form is a separate Stage, so the wall's scene snapshot cannot
     * see it. With {@code -Dcctv.form.snapshot=<path>} the form's own scene is written to a PNG.
     */
    private void captureForEvidence() {
        String target = System.getProperty("cctv.form.snapshot");
        if (target == null || target.isBlank() || stage.getScene() == null) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            javafx.scene.image.WritableImage image = stage.getScene().getRoot().snapshot(null, null);
            int width = (int) Math.ceil(image.getWidth());
            int height = (int) Math.ceil(image.getHeight());
            java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(width, height,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            javafx.scene.image.PixelReader reader = image.getPixelReader();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    buffered.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            try {
                javax.imageio.ImageIO.write(buffered, "png", java.nio.file.Path.of(target).toFile());
                log.info("camera form snapshot written to {}", target);
            } catch (java.io.IOException e) {
                log.warn("cannot write camera form snapshot: {}", e.toString());
            }
        });
    }

    private void populate() {
        if (existing == null) {
            enabledBox.setSelected(true);
            return;
        }
        nameField.setText(existing.name());
        urlField.setText(stripCredentials(existing.url()));
        substreamField.setText(stripCredentials(existing.substreamUrl()));
        if (existing.url() != null && existing.url().getUserInfo() != null) {
            String[] parts = existing.url().getUserInfo().split(":", 2);
            userField.setText(parts[0]);
            passwordField.setText(parts.length > 1 ? parts[1] : "");
        }
        recordBox.setSelected(existing.record());
        aiBox.setSelected(existing.ai());
        enabledBox.setSelected(existing.enabled());
        if (existing.enabled()) {
            testButton.setDisable(true);
            testButton.setTooltip(new javafx.scene.control.Tooltip(
                    "Disable the camera before testing: only one connection per stream is allowed"));
        }
    }

    private void runProbe() {
        URI uri;
        try {
            uri = buildUrl(urlField.getText(), false);
        } catch (IllegalArgumentException e) {
            status.setText("cannot test: " + e.getMessage());
            return;
        }
        testButton.setDisable(true);
        status.setText("testing connection ...");
        WORKER.execute(() -> {
            RtspProbe.Result result = RtspProbe.probe(uri, Duration.ofSeconds(8));
            Platform.runLater(() -> {
                status.setText(result.ok() ? "test: " + result.message()
                        : "test: " + result.message());
                testButton.setDisable(existing != null && existing.enabled());
            });
        });
    }

    private void save() {
        saveButton.setDisable(true);
        CameraStore.Draft draft;
        try {
            draft = new CameraStore.Draft(nameField.getText(), buildUrl(urlField.getText(), true),
                    buildUrl(substreamField.getText(), true), recordBox.isSelected(),
                    aiBox.isSelected(), enabledBox.isSelected());
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            saveButton.setDisable(false);
            return;
        }
        WORKER.execute(() -> {
            try {
                if (existing == null) {
                    long id = store.add(draft);
                    log.info("camera added: id {} name '{}'", id, draft.name());
                    Platform.runLater(() -> {
                        stage.close();
                        if (onSaved != null) {
                            onSaved.accept("camera '" + draft.name() + "' added");
                        }
                    });
                } else {
                    store.update(existing.id(), draft);
                    log.info("camera updated: id {} name '{}'", existing.id(), draft.name());
                    Platform.runLater(() -> {
                        stage.close();
                        if (onSaved != null) {
                            onSaved.accept("camera '" + draft.name() + "' saved");
                        }
                    });
                }
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? "cannot save camera" : e.getMessage();
                log.warn("camera save rejected: {}", message);
                Platform.runLater(() -> {
                    status.setText(message);
                    saveButton.setDisable(false);
                });
            }
        });
    }

    private static String stripCredentials(URI uri) {
        if (uri == null) {
            return "";
        }
        if (uri.getUserInfo() == null) {
            return uri.toString();
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(),
                    uri.getQuery(), uri.getFragment()).toString();
        } catch (URISyntaxException e) {
            return uri.toString();
        }
    }

    private URI buildUrl(String text, boolean allowEmpty) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            if (allowEmpty) {
                return null;
            }
            throw new IllegalArgumentException("URL is empty");
        }
        URI base;
        try {
            base = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL is not valid: " + e.getMessage());
        }
        String user = userField.getText() == null ? "" : userField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        if (user.isEmpty()) {
            return base;
        }
        try {
            return new URI(base.getScheme(), user + (password.isEmpty() ? "" : ":" + password),
                    base.getHost(), base.getPort(), base.getPath(), base.getQuery(),
                    base.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("cannot build URL with credentials: " + e.getMessage());
        }
    }
}
