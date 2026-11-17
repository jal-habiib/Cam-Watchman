package com.cctv.app.ui;

import com.cctv.app.db.ZoneDraft;
import com.cctv.app.db.ZoneStore;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 5c: the zone editor. A frozen frame (a still, never a live preview) is shown at a fixed
 * canvas size; the operator drags a rectangle, names it and saves. Rectangles are stored normalized
 * 0..1, so the same zone maps to any decode size the engine uses.
 */
public final class ZoneEditor extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(ZoneEditor.class);

    private static final double CANVAS_WIDTH = 800;
    private static final double CANVAS_HEIGHT = 450;
    private static final double MIN_SIZE = 0.02;
    private static final Color[] COLORS = {
            Color.web("#ff6a4d"), Color.web("#ffd166"), Color.web("#6ee7b7"),
            Color.web("#7dd3fc"), Color.web("#c4b5fd")};

    private final ZoneStore store;
    private final Consumer<String> onSaved;
    private final Pane canvas = new Pane();
    private final TextField nameField = new TextField();
    private final Label status = new Label();
    private final Rectangle rubber = new Rectangle();
    private final Label cameraLabel = new Label();
    private final CheckBox activeBox = new CheckBox("active");
    private final List<Draft> drafts = new ArrayList<>();

    private long cameraId;
    private String cameraName;
    private int selected = -1;
    private double startX;
    private double startY;
    private boolean dragging;

    private static final class Draft {
        String name;
        double x;
        double y;
        double w;
        double h;
        boolean active = true;

        Draft(String name, double x, double y, double w, double h) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    public ZoneEditor(ZoneStore store, Consumer<String> onSaved) {
        this.store = store;
        this.onSaved = onSaved;
        setVisible(false);
        setPickOnBounds(true);
        setStyle("-fx-background-color: rgba(0,0,0,0.62);");

        canvas.setPrefSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        canvas.setMinSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        canvas.setMaxSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        canvas.setStyle("-fx-background-color:#050505;");
        rubber.setFill(Color.web("#ffffff", 0.12));
        rubber.setStroke(Color.WHITE);
        rubber.setVisible(false);
        rubber.setMouseTransparent(true);
        canvas.getChildren().add(rubber);

        canvas.setOnMousePressed(event -> {
            if (event.isPrimaryButtonDown()) {
                dragging = true;
                startX = clamp(event.getX(), 0, CANVAS_WIDTH);
                startY = clamp(event.getY(), 0, CANVAS_HEIGHT);
                rubber.setX(startX);
                rubber.setY(startY);
                rubber.setWidth(0);
                rubber.setHeight(0);
                rubber.setVisible(true);
            }
        });
        canvas.setOnMouseDragged(event -> {
            if (!dragging) {
                return;
            }
            double x = clamp(event.getX(), 0, CANVAS_WIDTH);
            double y = clamp(event.getY(), 0, CANVAS_HEIGHT);
            rubber.setX(Math.min(startX, x));
            rubber.setY(Math.min(startY, y));
            rubber.setWidth(Math.abs(x - startX));
            rubber.setHeight(Math.abs(y - startY));
        });
        canvas.setOnMouseReleased(event -> {
            if (!dragging) {
                return;
            }
            dragging = false;
            rubber.setVisible(false);
            double x = Math.min(startX, clamp(event.getX(), 0, CANVAS_WIDTH));
            double y = Math.min(startY, clamp(event.getY(), 0, CANVAS_HEIGHT));
            double w = Math.abs(clamp(event.getX(), 0, CANVAS_WIDTH) - startX);
            double h = Math.abs(clamp(event.getY(), 0, CANVAS_HEIGHT) - startY);
            addDraft(x / CANVAS_WIDTH, y / CANVAS_HEIGHT, w / CANVAS_WIDTH, h / CANVAS_HEIGHT);
        });

        Label title = new Label("ZONE EDITOR");
        title.setStyle("-fx-text-fill:#f2f2f2; -fx-font-size:15px; -fx-font-weight:bold;");
        cameraLabel.setStyle("-fx-text-fill:#9a9a9a; -fx-font-size:11px;");
        Label hint = new Label("Drag a rectangle over the frozen frame.\n"
                + "Click a zone to select it, Delete removes it.\n"
                + "Coordinates are stored normalized 0..1.");
        hint.setStyle("-fx-text-fill:#8a8a8a; -fx-font-size:11px;");
        hint.setWrapText(true);

        nameField.setPromptText("zone name");
        nameField.setStyle("-fx-background-color:#0e0e0e; -fx-text-fill:#e8e8e8;"
                + " -fx-border-color:#333; -fx-border-radius:4; -fx-background-radius:4;"
                + " -fx-font-size:12px;");

        Button save = new Button("Save zones");
        save.setStyle("-fx-background-color:#1f6f43; -fx-text-fill:#f2f2f2; -fx-font-size:12px;"
                + " -fx-background-radius:4;");
        save.setOnAction(event -> save());
        Button cancel = new Button("Cancel");
        cancel.setStyle("-fx-background-color:#2a2a2a; -fx-text-fill:#e8e8e8; -fx-font-size:12px;"
                + " -fx-background-radius:4;");
        cancel.setOnAction(event -> close());
        Button clear = new Button("Clear");
        clear.setStyle("-fx-background-color:#2a2a2a; -fx-text-fill:#e8e8e8; -fx-font-size:12px;"
                + " -fx-background-radius:4;");
        clear.setOnAction(event -> {
            drafts.clear();
            selected = -1;
            redraw();
        });

        activeBox.setStyle("-fx-text-fill:#e8e8e8; -fx-font-size:12px;");
        activeBox.setSelected(true);
        activeBox.setOnAction(event -> {
            if (selected >= 0 && selected < drafts.size()) {
                drafts.get(selected).active = activeBox.isSelected();
                redraw();
            }
        });

        status.setStyle("-fx-text-fill:#8a8a8a; -fx-font-size:11px;");

        VBox panel = new VBox(10, title, cameraLabel, hint, nameField, activeBox, save, cancel, clear, status);
        panel.setPadding(new Insets(4));
        panel.setPrefWidth(256);
        panel.setMaxWidth(256);
        panel.setAlignment(Pos.TOP_LEFT);

        HBox row = new HBox(18, canvas, panel);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(16));
        row.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        row.setStyle("-fx-background-color:#111111; -fx-border-color:#2a2a2a;"
                + " -fx-border-radius:6; -fx-background-radius:6;");

        getChildren().add(row);
        StackPane.setAlignment(row, Pos.CENTER);
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                close();
            } else if (event.getCode() == KeyCode.DELETE) {
                removeSelected();
            } else if (event.getCode() == KeyCode.ENTER) {
                save();
            }
        });
        setFocusTraversable(true);
    }

    /** Opens the editor over {@code frame} for one camera and loads its saved zones. */
    public void open(long cameraId, String cameraName, BufferedImage frame) {
        this.cameraId = cameraId;
        this.cameraName = cameraName;
        drafts.clear();
        for (ZoneStore.Zone zone : store.forCamera(cameraId)) {
            Draft draft = new Draft(zone.name(), zone.x(), zone.y(), zone.w(), zone.h());
            draft.active = zone.active();
            drafts.add(draft);
        }
        selected = -1;
        cameraLabel.setText(cameraName + " · camera " + cameraId + " · " + drafts.size()
                + " saved zone(s)");
        canvas.getChildren().setAll(toImageView(frame), rubber);
        redraw();
        nameField.clear();
        status.setText("");
        setVisible(true);
        toFront();
        requestFocus();
        log.info("zone editor opened: camera {} ({} saved zones)", cameraId, drafts.size());
    }

    /** Adds a draft as if the operator had dragged it (evidence/demo runs only). */
    public void addDemoZone(String name, double x, double y, double w, double h) {
        addDraft(x, y, w, h);
        drafts.get(drafts.size() - 1).name = name;
        redraw();
    }

    public void close() {
        setVisible(false);
        dragging = false;
        rubber.setVisible(false);
    }

    public boolean isOpen() {
        return isVisible();
    }

    public long cameraId() {
        return cameraId;
    }

    private void addDraft(double x, double y, double w, double h) {
        if (w < MIN_SIZE || h < MIN_SIZE) {
            status.setText("zone too small - drag a larger rectangle");
            return;
        }
        if (x + w > 1.0) {
            w = 1.0 - x;
        }
        if (y + h > 1.0) {
            h = 1.0 - y;
        }
        String name = nameField.getText() == null || nameField.getText().isBlank()
                ? "zone " + (drafts.size() + 1) : nameField.getText().trim();
        drafts.add(new Draft(name, x, y, w, h));
        selected = drafts.size() - 1;
        activeBox.setSelected(true);
        status.setText("");
        redraw();
    }

    private void removeSelected() {
        if (selected >= 0 && selected < drafts.size()) {
            drafts.remove(selected);
            selected = -1;
            redraw();
        }
    }

    /** Persists every draft of this camera; returns false when validation rejects a rectangle. */
    public boolean save() {
        List<ZoneDraft> toSave = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            toSave.add(new ZoneDraft(draft.name, draft.x, draft.y, draft.w, draft.h, draft.active));
        }
        try {
            store.replaceForCamera(cameraId, toSave);
        } catch (IllegalArgumentException e) {
            status.setText("not saved: " + e.getMessage());
            return false;
        }
        log.info("zone editor saved {} zone(s) for camera {}", toSave.size(), cameraId);
        close();
        if (onSaved != null) {
            onSaved.accept(cameraName);
        }
        return true;
    }

    private void redraw() {
        // keep the frozen frame at index 0, then one outline + caption per draft
        canvas.getChildren().removeIf(node -> node != rubber && !(node instanceof ImageView));
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            Rectangle shape = new Rectangle(draft.x * CANVAS_WIDTH, draft.y * CANVAS_HEIGHT,
                    draft.w * CANVAS_WIDTH, draft.h * CANVAS_HEIGHT);
            Color color = COLORS[i % COLORS.length];
            shape.setFill(Color.web(color.toString(), 0.10));
            shape.setStroke(color);
            shape.setStrokeWidth(i == selected ? 3.0 : 1.6);
            if (!draft.active) {
                shape.getStrokeDashArray().addAll(6.0, 4.0);
                shape.setOpacity(0.55);
            }
            shape.setMouseTransparent(false);
            int index = i;
            shape.setOnMouseClicked(event -> {
                selected = index;
                nameField.setText(drafts.get(index).name);
                activeBox.setSelected(drafts.get(index).active);
                redraw();
                event.consume();
            });
            Label caption = new Label(draft.name);
            caption.setLayoutX(draft.x * CANVAS_WIDTH + 4);
            caption.setLayoutY(Math.max(0, draft.y * CANVAS_HEIGHT - 18));
            caption.setStyle("-fx-text-fill:" + toHex(color) + "; -fx-font-size:11px;"
                    + " -fx-background-color: rgba(0,0,0,0.55); -fx-padding: 1 4 1 4;");
            caption.setMouseTransparent(true);
            canvas.getChildren().addAll(shape, caption);
        }
        rubber.toFront();
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x", (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255), (int) (color.getBlue() * 255));
    }

    private static ImageView toImageView(BufferedImage source) {
        BufferedImage scaled = new BufferedImage((int) CANVAS_WIDTH, (int) CANVAS_HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, (int) CANVAS_WIDTH, (int) CANVAS_HEIGHT, null);
        g.dispose();
        WritableImage image = new WritableImage((int) CANVAS_WIDTH, (int) CANVAS_HEIGHT);
        PixelWriter writer = image.getPixelWriter();
        int[] row = new int[(int) CANVAS_WIDTH];
        for (int y = 0; y < CANVAS_HEIGHT; y++) {
            scaled.getRGB(0, y, (int) CANVAS_WIDTH, 1, row, 0, (int) CANVAS_WIDTH);
            writer.setPixels(0, y, (int) CANVAS_WIDTH, 1,
                    javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, (int) CANVAS_WIDTH);
        }
        ImageView view = new ImageView(image);
        view.setFitWidth(CANVAS_WIDTH);
        view.setFitHeight(CANVAS_HEIGHT);
        view.setMouseTransparent(true);
        return view;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
