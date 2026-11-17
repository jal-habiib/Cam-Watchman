package com.cctv.contract;

import java.util.List;

/**
 * Read-only view of the app's counting lines, handed to the engine (plan 7b).
 *
 * <p>The engine polls this once per inference frame (it must not do database I/O on that thread);
 * implementations are expected to cache and refresh after an edit, so the call stays cheap. Only
 * active lines are returned — an inactive line must never produce a crossing event.
 */
public interface LineSource {

    /** Active lines for one camera, never null (empty when none are configured). */
    List<CountLine> activeLines(long cameraId);
}
