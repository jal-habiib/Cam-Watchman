package com.cctv.app.ui;

import com.cctv.app.count.CountLineStore;
import com.cctv.app.db.LineDraft;
import com.cctv.contract.CountLine;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 7b: count-line editor on a frozen camera frame.
 *
 * <p>Drag from one point to another to draw a line; click a line to select it, Delete removes it.
 * Names and the active flag are edited in the side panel and saved together for the whole camera,
 * matching {@link ZoneEditor}'s replace-on-save semantics. Coordinates are normalized 0..1.
 */
public final class LineEditor extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(LineEditor.class);
    private static final int CANVAS_WIDTH = 800;
    private static final int CANVAS_HEIGHT = 450;
    private static final double MIN_LENGTH = 0.02;
    private static final Color LINE_COLOR = Color.web("#7ee787");

    private static final class Draft {
        String name;
        double x1;
        double y1;
        double x2;
        double y2;
        boolean active = true;

        Draft(String name, double x1, double y1, double x2, double y2, boolean active) {
            this.name = name;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.active = active;
        }

        boolean sameAs(CountLine line) {
            return Math.abs(line.x1() - x1) < 1e-6 && Math.abs(line.y1() - y1) < 1e-6
                    && Math.abs(line.x2() - x2) < 1e-6 && Math.abs(line.y2() - y2) < 1e-6;
        }
    }

    private final CountLineStore store;
    private final Canvas canvas = new Canvas(CANVAS_WIDTH, CANVAS_HEIGHT);
    private final TextField nameField = new TextField();
    private final CheckBox activeBox = new CheckBox("active");
    private final Label cameraLabel = new Label();
    private final Label status = new Label();
    private final List<Draft> drafts = new ArrayList<>();
    private final Runnable onClose;
    private java.util.function.Consumer<String> onSaved;
    private long cameraId;
    private int selected = -1;
    private double dragX;
    private double dragY;
    private boolean dragging;

    public LineEditor(CountLineStore store, Runnable onClose) {
        this.store = store;
        this.onClose = onClose;        getStyleClass().add("overlay-panel");
        setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(
                Color.web("#0f1317"), null, null)));

        StackPane canvasPane = new StackPane(canvas);
        canvasPane.getStyleClass().add("overlay-canvas");
        canvasPane.setMaxSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        canvasPane.setMinSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        HBox.setHgrow(canvasPane, Priority.NEVER);

        nameField.setPromptText("line name");
        activeBox.setSelected(true);
        Button save = new Button("Save lines");
        save.setOnAction(e -> save());
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> close());
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            drafts.clear();
            selected = -1;
            draw();
            status.setText("cleared - Save lines to apply");
        });

        VBox panel = new VBox(8,
                title("LINE EDITOR"),
                cameraLabel,
                hint("Drag across the frozen frame to draw a counting line."),
                hint("Click a line to select it, Delete removes it."),
                hint("Direction follows the drag: start to end is IN."),
                nameField,
                activeBox,
                save,
                cancel,
                clear,
                status);
        panel.setPadding(new javafx.geometry.Insets(0, 0, 0, 14));
        panel.setPrefWidth(256);
        panel.setMaxWidth(256);
        status.getStyleClass().add("hint");
        status.setStyle("-fx-text-fill:#d29922; -fx-font-size:11px;");
        cameraLabel.setStyle("-fx-text-fill:#c9d1d9; -fx-font-size:11px;");

        HBox row = new HBox(14, canvasPane, panel);
        row.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        row.setPadding(new javafx.geometry.Insets(16));
        getChildren().add(row);
        setPickOnBounds(true);

        canvas.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            nudgeSelection(e.getX(), e.getY());
            double nx = clamp(e.getX() / CANVAS_WIDTH);
            double ny = clamp(e.getY() / CANVAS_HEIGHT);
            if (selected < 0) {
                dragging = true;
                dragX = nx;
                dragY = ny;
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (dragging) {
                draftPreview(clamp(e.getX() / CANVAS_WIDTH), clamp(e.getY() / CANVAS_HEIGHT));
            }
        });
        canvas.setOnMouseReleased(e -> {
            if (!dragging) {
                return;
            }
            dragging = false;
            double nx = clamp(e.getX() / CANVAS_WIDTH);
            double ny = clamp(e.getY() / CANVAS_HEIGHT);
            if (Math.hypot(nx - dragX, ny - dragY) < MIN_LENGTH) {
                draw();
                status.setText("line too short");
                return;
            }
            Draft draft = new Draft(defaultName(), dragX, dragY, nx, ny, activeBox.isSelected());
            drafts.add(draft);
            selected = drafts.size() - 1;
            nameField.setText(draft.name);
            draw();
            status.setText("line added - Save lines to apply");
        });
        canvas.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.DELETE && selected >= 0) {
                drafts.remove(selected);
                selected = -1;
                draw();
                status.setText("line removed - Save lines to apply");
            }
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                close();
            } else if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                save();
            }
        });
        setFocusTraversable(true);
        setVisible(false);
    }

    /** Called after a successful save so the live overlay can redraw the new line. */
    public void setOnSaved(java.util.function.Consumer<String> onSaved) {
        this.onSaved = onSaved;
    }

    private static Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("overlay-title");
        return label;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hint");
        label.setWrapText(true);
        return label;
    }

    /** Opens the editor for one camera over a frozen frame. */
    public void open(long cameraId, String cameraName, BufferedImage frame) {
        this.cameraId = cameraId;
        cameraLabel.setText(cameraName + " · camera " + cameraId);
        drafts.clear();
        selected = -1;
        nameField.clear();
        activeBox.setSelected(true);
        status.setText("");
        try {
            for (CountLine line : store.forCamera(cameraId)) {
                drafts.add(new Draft(line.name(), line.x1(), line.y1(), line.x2(), line.y2(),
                        line.active()));
            }
        } catch (java.sql.SQLException e) {
            log.warn("cannot load count lines for camera {}: {}", cameraId, e.toString());
            status.setText("cannot load saved lines");
        }
        canvas.setFocusTraversable(true);
        drawFrozenFrame(frame);
        draw();
        setVisible(true);
        canvas.requestFocus();
        log.info("line editor opened: camera {} ({} saved line(s))", cameraId, drafts.size());
    }

    /** Adds an example line, used by the startup demo switch and headless evidence runs. */
    public void addDemoLine(String name, double x1, double y1, double x2, double y2) {
        drafts.add(new Draft(name, x1, y1, x2, y2, true));
        draw();
        log.info("line editor demo line added: {} -> ({},{}),({},{})", name, x1, y1, x2, y2);
    }

    public boolean save() {
        syncName();
        List<LineDraft> toSave = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            toSave.add(new LineDraft(draft.name, draft.x1, draft.y1, draft.x2, draft.y2, draft.active));
        }
        try {
            store.replaceForCamera(cameraId, toSave);
        } catch (IllegalArgumentException e) {
            status.setText("not saved: " + e.getMessage());
            return false;
        }
        log.info("line editor saved {} line(s) for camera {}", toSave.size(), cameraId);
        close();
        if (onSaved != null) {
            onSaved.accept(cameraLabel.getText());
        }
        return true;
    }

    public void close() {
        setVisible(false);
        if (onClose != null) {
            onClose.run();
        }
    }

    public long cameraId() {
        return cameraId;
    }

    public boolean isOpen() {
        return isVisible();
    }

    private void syncName() {
        if (selected >= 0 && selected < drafts.size() && !nameField.getText().isBlank()) {
            drafts.get(selected).name = nameField.getText().trim();
        }
    }

    private String defaultName() {
        return "line " + (drafts.size() + 1);
    }

    private void nudgeSelection(double px, double py) {
        double nx = px / CANVAS_WIDTH;
        double ny = py / CANVAS_HEIGHT;
        double best = 0.02;
        int bestIndex = -1;
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            double d = distanceToSegment(nx, ny, draft.x1, draft.y1, draft.x2, draft.y2);
            if (d < best) {
                best = d;
                bestIndex = i;
            }
        }
        selected = bestIndex;
        if (selected >= 0) {
            Draft draft = drafts.get(selected);
            nameField.setText(draft.name);
            activeBox.setSelected(draft.active);
            status.setText("selected " + draft.name);
        }
    }

    private static double distanceToSegment(double px, double py, double x1, double y1, double x2,
                                            double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSq = dx * dx + dy * dy;
        if (lengthSq == 0) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lengthSq));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private void draftPreview(double nx, double ny) {
        draw();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setStroke(LINE_COLOR);
        g.setLineWidth(3);
        g.setLineDashes();
        g.strokeLine(dragX * CANVAS_WIDTH, dragY * CANVAS_HEIGHT, nx * CANVAS_WIDTH,
                ny * CANVAS_HEIGHT);
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        clearCanvas();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            boolean isSelected = i == selected;
            g.setStroke(isSelected ? Color.WHITE : LINE_COLOR);
            g.setLineWidth(isSelected ? 4 : 2.5);
            if (draft.active) {
                g.setLineDashes();
            } else {
                g.setLineDashes(10, 8);
            }
            g.strokeLine(draft.x1 * CANVAS_WIDTH, draft.y1 * CANVAS_HEIGHT,
                    draft.x2 * CANVAS_WIDTH, draft.y2 * CANVAS_HEIGHT);
            // endpoint markers: the first point is the IN side reference
            g.setFill(isSelected ? Color.WHITE : LINE_COLOR);
            g.fillOval(draft.x1 * CANVAS_WIDTH - 4, draft.y1 * CANVAS_HEIGHT - 4, 8, 8);
            g.setFill(Color.TRANSPARENT);
            g.strokeOval(draft.x2 * CANVAS_WIDTH - 4, draft.y2 * CANVAS_HEIGHT - 4, 8, 8);
            g.setLineDashes();
            g.setFill(Color.web("#0b0f14", 0.75));
            g.fillRect(draft.x1 * CANVAS_WIDTH - 2, draft.y1 * CANVAS_HEIGHT - 20,
                    Math.max(60, draft.name.length() * 7), 16);
            g.setFill(isSelected ? Color.WHITE : Color.web("#c9d1d9"));
            g.fillText(draft.name, draft.x1 * CANVAS_WIDTH + 2, draft.y1 * CANVAS_HEIGHT - 7);
        }
    }

    private WritableImage frozen;

    private void drawFrozenFrame(BufferedImage frame) {
        if (frame == null) {
            clearCanvas();
            return;
        }
        frozen = new WritableImage(CANVAS_WIDTH, CANVAS_HEIGHT);
        int[] pixels = new int[CANVAS_WIDTH * CANVAS_HEIGHT];
        for (int y = 0; y < CANVAS_HEIGHT; y++) {
            int sy = y * frame.getHeight() / (int) CANVAS_HEIGHT;
            for (int x = 0; x < CANVAS_WIDTH; x++) {
                int sx = x * frame.getWidth() / (int) CANVAS_WIDTH;
                pixels[y * CANVAS_WIDTH + x] = frame.getRGB(sx, sy);
            }
        }
        frozen.getPixelWriter().setPixels(0, 0, (int) CANVAS_WIDTH, (int) CANVAS_HEIGHT,
                PixelFormat.getIntArgbInstance(), pixels, 0, (int) CANVAS_WIDTH);
        canvas.getGraphicsContext2D().drawImage(frozen, 0, 0);
    }

    private void clearCanvas() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        if (frozen != null) {
            g.drawImage(frozen, 0, 0);
        } else {
            g.setFill(Color.web("#0b0f14"));
            g.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
