package com.cctv.contract;

import java.util.List;

/**
 * Read-only view of the active intrusion zones of one camera, polled by the engine once per
 * inference frame. Implementations must be cheap and must never do I/O on the caller's thread
 * (the app answers from a short-lived cache, so an edited zone is visible within a couple of
 * seconds without a database read per frame).
 */
@FunctionalInterface
public interface ZoneSource {

    /** Active zones only; empty when the camera has none or the store is unreadable. */
    List<ZoneDefinition> activeZones(long cameraId);
}
