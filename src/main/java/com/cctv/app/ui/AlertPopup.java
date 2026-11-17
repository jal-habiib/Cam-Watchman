package com.cctv.app.ui;

import com.cctv.contract.ZoneAlert;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Phase 5d: the in-app intrusion popup. An overlay layer (not a second window) so it belongs to
 * the wall scene — which keeps it inside the app, above the video cells and captured by the
 * screenshot evidence runs.
 */
public final class AlertPopup extends StackPane {

    private static final int AUTO_HIDE_SECONDS = 10;
    private static final double IMAGE_WIDTH = 560;
    private static final double IMAGE_HEIGHT = 315;

    private final ImageView snapshot = new ImageView();
    private final Label zoneLabel = new Label();
    private final Label metaLabel = new Label();
    private final PauseTransition autoHide = new PauseTransition(Duration.seconds(AUTO_HIDE_SECONDS));

    public AlertPopup() {
        setVisible(false);
        setPickOnBounds(true);
        setStyle("-fx-background-color: rgba(0,0,0,0.55);");

        Label title = new Label("INTRUSION");
        title.setStyle("-fx-text-fill:#ff6a4d; -fx-font-size:13px; -fx-font-weight:bold;");

        snapshot.setFitWidth(IMAGE_WIDTH);
        snapshot.setFitHeight(IMAGE_HEIGHT);
        snapshot.setPreserveRatio(true);
        snapshot.setSmooth(true);

        zoneLabel.setStyle("-fx-text-fill:#f5f5f5; -fx-font-size:16px; -fx-font-weight:bold;");
        metaLabel.setStyle("-fx-text-fill:#9a9a9a; -fx-font-size:11px;");

        Button dismiss = new Button("Dismiss");
        dismiss.setStyle("-fx-background-color:#2a2a2a; -fx-text-fill:#e8e8e8; -fx-font-size:11px;"
                + " -fx-border-color:#444; -fx-border-radius:4; -fx-background-radius:4;");
        dismiss.setOnAction(event -> dismiss());

        VBox card = new VBox(8, title, snapshot, zoneLabel, metaLabel, dismiss);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Region.USE_PREF_SIZE);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setPadding(new Insets(14));
        card.setStyle("-fx-background-color:#151515; -fx-border-color:#ff6a4d; -fx-border-width:2;"
                + " -fx-border-radius:6; -fx-background-radius:6;");

        getChildren().add(card);
        StackPane.setAlignment(card, Pos.CENTER);
        autoHide.setOnFinished(event -> setVisible(false));
        setOnMouseClicked(event -> {
            if (event.getTarget() == this) {
                dismiss();
            }
        });
    }

    /** Shows the alert card; called on the JavaFX thread. */
    public void show(ZoneAlert alert, String zoneName, String cameraName) {
        if (alert.snapshotFile() != null) {
            Image image;
            try {
                // backgroundLoading=false: the PNG is local and small, and evidence snapshots must
                // never catch an empty ImageView.
                image = new Image(alert.snapshotFile().toUri().toString(), IMAGE_WIDTH, IMAGE_HEIGHT,
                        true, true, false);
            } catch (RuntimeException e) {
                image = null;
            }
            snapshot.setImage(image != null && !image.isError() ? image : null);
        } else {
            snapshot.setImage(null);
        }
        zoneLabel.setText(zoneName == null || zoneName.isBlank()
                ? "zone " + alert.zoneId() : zoneName);
        String when = DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(alert.createdUtcMs()));
        metaLabel.setText(cameraName + " · " + when + " · "
                + (alert.recordingStartUtcMs() > 0 ? "clip linked" : "no clip"));
        setVisible(true);
        toFront();
        autoHide.playFromStart();
    }

    /** Hides the card without touching any stream. */
    public void dismiss() {
        autoHide.stop();
        setVisible(false);
    }

    public boolean isShowing() {
        return isVisible();
    }
}
