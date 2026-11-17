package com.cctv.spike;

import javafx.application.Application;

/** Launcher: JavaFX refuses to start from a class that extends Application when on the classpath. */
public final class JavaCvSpike {
    public static void main(String[] args) {
        Application.launch(JavaCvSpikeApp.class, args);
    }
}
