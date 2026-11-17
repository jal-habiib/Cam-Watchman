package com.cctv.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves the bundled YOLO model independently of the launcher's working directory. */
public final class ModelPathResolver {
    private static final Path MODEL_RELATIVE_PATH = Path.of("models", "yolo11n.onnx");

    private ModelPathResolver() {
    }

    public static Path resolve(String configuredPath, Path codeLocation, Path workingDirectory)
            throws IOException {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath);
            return (configured.isAbsolute() ? configured : workingDirectory.resolve(configured))
                    .toAbsolutePath().normalize();
        }

        Path workingCandidate = workingDirectory.resolve(MODEL_RELATIVE_PATH).toAbsolutePath().normalize();
        if (Files.isRegularFile(workingCandidate)) {
            return workingCandidate;
        }

        Path location = codeLocation == null ? null : codeLocation.toAbsolutePath().normalize();
        if (location != null && !Files.isDirectory(location)) {
            location = location.getParent();
        }
        for (int depth = 0; location != null && depth < 8; depth++, location = location.getParent()) {
            Path candidate = location.resolve(MODEL_RELATIVE_PATH).normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        throw new IOException("model not found: searched working directory and application bundle "
                + "(expected models/yolo11n.onnx; see models/README.md)");
    }
}
