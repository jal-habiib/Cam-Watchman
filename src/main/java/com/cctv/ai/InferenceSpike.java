package com.cctv.ai;

import ai.onnxruntime.OrtException;
import com.cctv.contract.Detection;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Spike 0c: run one still image through yolo11n.onnx and print the detections, drawing the
 * boxes back onto the image so they can be checked by eye.
 *
 * <p>Usage: {@code InferenceSpike [model] [image] [annotated-output]}; defaults are
 * {@code models/yolo11n.onnx}, {@code testdata/bus.jpg},
 * {@code target/spike/bus-detected.jpg}.
 *
 * <p>Run it with the project classpath, e.g.:
 * <pre>
 * mvnw.cmd -q -DskipTests compile
 * mvnw.cmd -q exec:java -Dexec.mainClass=com.cctv.ai.InferenceSpike   (exec plugin not in pom)
 * </pre>
 * or, without touching the pom, through the surefire test classpath or
 * {@code java -cp target\classes;...}. The report in {@code testdata/README.md} lists the
 * exact command used.
 */
public final class InferenceSpike {

    private InferenceSpike() {
    }

    public static void main(String[] args) throws Exception {
        Path model = Path.of(args.length > 0 ? args[0] : "models/yolo11n.onnx");
        Path imagePath = Path.of(args.length > 1 ? args[1] : "testdata/bus.jpg");
        Path outPath = Path.of(args.length > 2 ? args[2]
                : "target/spike/" + baseName(imagePath) + "-detected.jpg");

        if (!Files.isRegularFile(imagePath)) {
            System.err.println("image not found: " + imagePath.toAbsolutePath());
            System.exit(2);
        }
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) {
            System.err.println("not a readable image: " + imagePath);
            System.exit(2);
        }

        System.out.println("image : " + imagePath.toAbsolutePath()
                + "  " + image.getWidth() + "x" + image.getHeight());
        System.out.println("model : " + model.toAbsolutePath());

        long loadStart = System.nanoTime();
        try (YoloDetector detector = YoloDetector.load(model)) {
            long loadNanos = System.nanoTime() - loadStart;
            System.out.printf(Locale.ROOT, "input : %d x %d (read from model)%n",
                    detector.inputWidth(), detector.inputHeight());
            System.out.println("output: " + java.util.Arrays.toString(detector.outputShape())
                    + " (read from model)");
            System.out.println("conf  : " + detector.confThreshold()
                    + "   NMS IoU: " + detector.nmsIou());
            System.out.printf(Locale.ROOT, "load  : %.1f ms%n", loadNanos / 1e6);

            // Warm-up run, then one measured run (the JVM/JIT and ORT graph are lazy).
            detector.detectTimed(image, 1L, System.currentTimeMillis());
            YoloDetector.Inference inference =
                    detector.detectTimed(image, 1L, System.currentTimeMillis());
            List<Detection> detections = inference.detections();

            System.out.printf(Locale.ROOT,
                    "latency: preprocess %.1f ms, inference %.1f ms, postprocess %.2f ms, total %.1f ms%n",
                    inference.preprocessNanos() / 1e6, inference.runNanos() / 1e6,
                    inference.postprocessNanos() / 1e6, inference.totalNanos() / 1e6);
            System.out.println("detections: " + detections.size());
            for (Detection d : detections) {
                System.out.printf(Locale.ROOT,
                        "  %-10s %.3f  box=(%.4f, %.4f, %.4f, %.4f)  px=(%d, %d) - (%d, %d)%n",
                        d.objectClass(), d.confidence(), d.x1(), d.y1(), d.x2(), d.y2(),
                        Math.round(d.x1() * image.getWidth()),
                        Math.round(d.y1() * image.getHeight()),
                        Math.round(d.x2() * image.getWidth()),
                        Math.round(d.y2() * image.getHeight()));
            }

            BufferedImage annotated = draw(image, detections);
            Files.createDirectories(outPath.toAbsolutePath().getParent());
            ImageIO.write(annotated, "jpg", outPath.toFile());
            System.out.println("annotated: " + outPath.toAbsolutePath());

            if (detections.isEmpty()) {
                System.err.println("SPIKE FAILED: no detections");
                System.exit(1);
            }
            boolean personOrCar = detections.stream()
                    .anyMatch(d -> d.objectClass().equals("person") || d.objectClass().equals("car"));
            System.out.println(personOrCar ? "SPIKE PASS: person/car detected" :
                    "SPIKE PASS (but only truck/motorcycle)");
        } catch (OrtException e) {
            System.err.println("ONNX Runtime error: " + e);
            System.exit(3);
        } catch (IOException e) {
            System.err.println("I/O error: " + e);
            System.exit(3);
        }
    }

    /** Draws the normalized boxes onto a copy of the image. */
    public static BufferedImage draw(BufferedImage source, List<Detection> detections) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(Math.max(2f, source.getWidth() / 300f)));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, source.getWidth() / 60)));
            for (Detection d : detections) {
                int x1 = (int) Math.round(d.x1() * source.getWidth());
                int y1 = (int) Math.round(d.y1() * source.getHeight());
                int x2 = (int) Math.round(d.x2() * source.getWidth());
                int y2 = (int) Math.round(d.y2() * source.getHeight());
                Color color = switch (d.objectClass()) {
                    case "person" -> new Color(0, 220, 0);
                    case "car" -> new Color(60, 160, 255);
                    case "truck" -> new Color(255, 180, 0);
                    default -> new Color(255, 80, 80);
                };
                g.setColor(color);
                g.drawRect(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
                String label = String.format(Locale.ROOT, "%s %.2f", d.objectClass(), d.confidence());
                int textY = Math.max(14, y1 - 4);
                g.fillRect(x1, textY - 14, g.getFontMetrics().stringWidth(label) + 6, 16);
                g.setColor(Color.BLACK);
                g.drawString(label, x1 + 3, textY - 2);
            }
        } finally {
            g.dispose();
        }
        return copy;
    }

    private static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
