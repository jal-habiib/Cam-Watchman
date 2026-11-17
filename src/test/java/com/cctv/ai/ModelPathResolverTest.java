package com.cctv.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelPathResolverTest {
    @TempDir
    Path temp;

    @Test
    void resolvesModelBesidePackagedAppWhenWorkingDirectoryIsUnrelated() throws Exception {
        Path app = Files.createDirectories(temp.resolve("install/CamWatchman"));
        Path appDir = Files.createDirectories(app.resolve("app"));
        Path jar = Files.createFile(appDir.resolve("cam-watchman.jar"));
        Path model = Files.createDirectories(app.resolve("models")).resolve("yolo11n.onnx");
        Files.createFile(model);
        Path unrelatedWorkingDirectory = Files.createDirectories(temp.resolve("unrelated/working-dir"));

        assertEquals(model.toAbsolutePath().normalize(),
                ModelPathResolver.resolve(null, jar, unrelatedWorkingDirectory));
    }

    @Test
    void resolvesModelFromMavenClassesLocation() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path classes = Files.createDirectories(project.resolve("target/classes"));
        Path model = Files.createDirectories(project.resolve("models")).resolve("yolo11n.onnx");
        Files.createFile(model);

        assertEquals(model.toAbsolutePath().normalize(),
                ModelPathResolver.resolve(null, classes, temp));
    }

    @Test
    void explicitRelativeOverrideRemainsRelativeToWorkingDirectory() throws Exception {
        Path workingDirectory = Files.createDirectories(temp.resolve("work"));
        Path expected = workingDirectory.resolve("custom/model.onnx").toAbsolutePath().normalize();

        assertEquals(expected,
                ModelPathResolver.resolve("custom/model.onnx", null, workingDirectory));
    }

    @Test
    void failsClearlyWhenModelIsMissing() throws Exception {
        Path workingDirectory = Files.createDirectories(temp.resolve("empty"));

        assertThrows(IOException.class,
                () -> ModelPathResolver.resolve(null, workingDirectory, workingDirectory));
    }
}
