package com.cctv.app.alert;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 5d: the alert chime. A short generated two-tone chirp played on a daemon thread, so a
 * machine without a usable sound device degrades to a log line instead of breaking the alert.
 */
public final class AlertSound {

    private static final Logger log = LoggerFactory.getLogger(AlertSound.class);

    private static final float SAMPLE_RATE = 44_100f;
    private static final double DURATION_SECONDS = 0.45;

    private static volatile Clip clip;
    private static volatile boolean warned;

    private AlertSound() {
    }

    /** Plays the chime once; returns immediately. */
    public static void play() {
        Thread thread = new Thread(AlertSound::playOnce, "alert-sound");
        thread.setDaemon(true);
        thread.start();
    }

    private static void playOnce() {
        try {
            Clip current = clip;
            if (current == null) {
                current = createClip();
                clip = current;
            }
            if (current == null) {
                return;
            }
            current.stop();
            current.setFramePosition(0);
            current.start();
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    private static Clip createClip() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            int frames = (int) (SAMPLE_RATE * DURATION_SECONDS);
            byte[] pcm = new byte[frames * 2];
            for (int i = 0; i < frames; i++) {
                double t = i / SAMPLE_RATE;
                double frequency = 880.0 + 320.0 * (t / DURATION_SECONDS);
                double fadeIn = Math.min(1.0, t / 0.02);
                double fadeOut = Math.max(0.0, (DURATION_SECONDS - t) / 0.06);
                double envelope = Math.min(fadeIn, fadeOut);
                double sample = (Math.sin(2 * Math.PI * frequency * t) * 0.35
                        + Math.sin(2 * Math.PI * frequency * 2 * t) * 0.08) * envelope;
                short value = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                        sample * Short.MAX_VALUE));
                pcm[2 * i] = (byte) (value & 0xFF);
                pcm[2 * i + 1] = (byte) ((value >> 8) & 0xFF);
            }
            Clip created = AudioSystem.getClip();
            created.open(format, pcm, 0, pcm.length);
            return created;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            warnOnce(e);
            return null;
        }
    }

    private static void warnOnce(Exception e) {
        if (!warned) {
            warned = true;
            log.warn("alert sound unavailable ({}), continuing silently", e.toString());
        }
    }
}
