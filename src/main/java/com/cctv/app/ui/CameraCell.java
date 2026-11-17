package com.cctv.app.ui;

import com.cctv.app.config.CameraConfig;
import com.cctv.contract.StreamStatusEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * One wall cell: camera name, stream/record badge and (for now) a placeholder until a video
 * surface is attached in Phase 1/6. Styling is inline so the cell has no stylesheet dependency.
 */
public final class CameraCell extends BorderPane {

    private static final String BG = "-fx-background-color:#141414; -fx-background-radius:6;";
    private static final String BORDER = "-fx-border-color:#2b2b2b; -fx-border-radius:6; -fx-border-width:1;";

    private final long cameraId;
    private final Label nameLabel = new Label();
    private final Label badge = new Label("OFFLINE");
    private final Label placeholder = new Label();
    private final StackPane videoArea = new StackPane();
    private StreamStatusEvent lastStatus;
    private boolean recording;

    public CameraCell(CameraConfig camera) {
        this.cameraId = camera.id();
        this.lastStatus = StreamStatusEvent.now(cameraId, com.cctv.contract.StreamState.OFFLINE, "offline");
        setStyle(BG + BORDER);
        setPadding(new Insets(8));

        nameLabel.setText(camera.name() + "  ·  " + camera.id());
        nameLabel.setStyle("-fx-text-fill:#e8e8e8; -fx-font-size:13px; -fx-font-weight:bold;");

        badge.setStyle(badgeStyle("#4a4a4a"));
        badge.setPadding(new Insets(2, 8, 2, 8));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, nameLabel, spacer, badge);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 6, 0));

        placeholder.setText(camera.displayUrl() == null
                ? "No stream URL configured"
                : "No video yet — stream wiring lands with Phase 1a/6a");
        placeholder.setStyle("-fx-text-fill:#6f6f6f; -fx-font-size:11px;");
        videoArea.setStyle("-fx-background-color:#0b0b0b; -fx-background-radius:4;");
        videoArea.getChildren().add(placeholder);
        videoArea.setMinSize(160, 90);

        Label url = new Label(camera.displayUrl() == null ? "" : com.cctv.app.config.Redaction.redact(camera.displayUrl().toString()));
        url.setStyle("-fx-text-fill:#5a5a5a; -fx-font-size:10px;");

        VBox below = new VBox(2, url);
        below.setPadding(new Insets(6, 0, 0, 0));

        setTop(header);
        setCenter(videoArea);
        setBottom(below);
        VBox.setVgrow(this, Priority.ALWAYS);
    }

    /**
     * Applies a connectivity event coming off the event bus. Must be called on the FX thread.
     * Recording is not a stream state (contract v0.2): the RECORDING badge comes from segment
     * lifecycle events through {@link #setRecording(boolean)}.
     */
    public void setState(StreamStatusEvent status) {
        lastStatus = status;
        String text = switch (status.state()) {
            case CONNECTING -> "CONNECTING";
            case ONLINE -> "ONLINE";
            case OFFLINE -> "OFFLINE";
            case RECONNECTING -> "RECONNECTING";
            case ERROR -> "ERROR";
        };
        String colour = switch (status.state()) {
            case CONNECTING -> "#3d3d3d";
            case ONLINE -> "#1b5e20";
            case OFFLINE -> "#4a4a4a";
            case RECONNECTING -> "#8a6d00";
            case ERROR -> "#7a1f1f";
        };
        badge.setText(recording ? text + " \u00b7 REC" : text);
        badge.setStyle(badgeStyle(recording ? "#b26a00" : colour));
        if (status.detail() != null && !status.detail().isBlank()) {
            placeholder.setText(status.detail());
        }
    }

    /** True while this camera is writing segments; drawn as an orange REC badge. */
    public void setRecording(boolean active) {
        if (recording == active) {
            return;
        }
        recording = active;
        if (lastStatus != null) {
            setState(lastStatus);
        }
    }

    public boolean isRecording() {
        return recording;
    }

    public long cameraId() {
        return cameraId;
    }

    /** Area a future video surface should be attached to. */
    public StackPane videoArea() {
        return videoArea;
    }

    /**
     * Replaces the placeholder with a live video surface (Phase 6: the VLCJ cell for a
     * display-only camera). Must be called on the FX thread.
     */
    public void setVideo(javafx.scene.Node video) {
        videoArea.getChildren().clear();
        videoArea.getChildren().add(video);
    }

    private static String badgeStyle(String colour) {
        return "-fx-background-color:" + colour + "; -fx-background-radius:999;"
                + " -fx-text-fill:white; -fx-font-size:10px; -fx-font-weight:bold;";
    }
}
