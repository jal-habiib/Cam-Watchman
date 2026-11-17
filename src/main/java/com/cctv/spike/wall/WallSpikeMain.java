package com.cctv.spike.wall;

/**
 * Launcher for the 0d spike.
 *
 * <p>A plain main class (not an {@link javafx.application.Application}) so the spike can be
 * started with {@code java -cp ...} without JavaFX module-path gymnastics.
 */
public final class WallSpikeMain {

    private WallSpikeMain() {
    }

    public static void main(String[] args) {
        javafx.application.Application.launch(VlcjWallSpike.class, args);
    }
}
