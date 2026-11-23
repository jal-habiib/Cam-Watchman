package com.cctv.contract;

import java.util.Optional;

/**
 * The only way frames leave one camera's main RTSP connection.
 *
 * <p>Implementations keep a bounded handoff and drop stale frames rather than blocking the
 * decode loop. {@link #next()} therefore returns the newest fresh frame, or empty when none
 * is available at the consumer's configured rate. A source may use a one-slot latest-frame
 * handoff when there is a single sampled consumer.
 */
public interface FrameSource extends AutoCloseable {

    /** Next fresh frame, or empty if none is due/available. Never blocks the decode loop. */
    Optional<Frame> next();

    @Override
    default void close() {
        // no-op
    }
}
