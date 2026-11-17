package com.cctv.app.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

/**
 * Process-wide VLCJ native lifecycle. VLC is loaded once; every display-only camera then creates
 * its own {@link uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer} on this factory
 * (plan §Locked decisions: one VLCJ connection per substream, never a second one to the same URL).
 */
public final class VlcjRuntime {

    private static final Logger log = LoggerFactory.getLogger(VlcjRuntime.class);
    private static final String VLC_DIR = "C:\\Program Files\\VideoLAN\\VLC";

    private static MediaPlayerFactory factory;

    private VlcjRuntime() {
    }

    /** True when the native VLC libraries could be located. */
    public static synchronized boolean available() {
        return ensure() != null;
    }

    public static synchronized MediaPlayerFactory factory() {
        MediaPlayerFactory created = ensure();
        if (created == null) {
            throw new IllegalStateException("VLC native libraries were not found");
        }
        return created;
    }

    private static MediaPlayerFactory ensure() {
        if (factory != null) {
            return factory;
        }
        boolean found = new NativeDiscovery().discover();
        if (!found) {
            log.warn("NativeDiscovery did not find VLC; trying {}", VLC_DIR);
            System.setProperty("jna.library.path", VLC_DIR);
        }
        try {
            factory = new MediaPlayerFactory("--no-audio", "--no-video-title-show", "--quiet", "--no-osd",
                    "--network-caching=250");
            log.info("VLCJ ready (native discovery: {})", found);
            return factory;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            log.error("VLCJ could not create a media player factory", e);
            return null;
        }
    }

    public static synchronized void release() {
        if (factory != null) {
            try {
                factory.release();
            } catch (RuntimeException e) {
                log.warn("releasing the VLCJ factory failed: {}", e.toString());
            }
            factory = null;
        }
    }
}
