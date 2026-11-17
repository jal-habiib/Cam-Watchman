package com.cctv.app.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Free-space probe used by the retention guard (plan 3c: pause below the configured minimum). */
public final class FreeSpace {

    private static final Logger log = LoggerFactory.getLogger(FreeSpace.class);

    private FreeSpace() {
    }

    /**
     * Usable bytes on the file store that holds {@code dir}. If the value cannot be read the guard
     * is disabled rather than blocking recording: losing footage is worse than a full disk warning.
     */
    public static long usableBytes(Path dir) {
        try {
            Files.createDirectories(dir);
            return Files.getFileStore(dir).getUsableSpace();
        } catch (IOException e) {
            log.warn("cannot read free space for {}: {}", dir, e.toString());
            return Long.MAX_VALUE;
        }
    }
}
