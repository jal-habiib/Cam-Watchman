package com.cctv.contract;

/**
 * Connectivity of one stream profile, published as {@link StreamStatusEvent}.
 *
 * <p>Recording is deliberately absent: it is a segment-lifecycle fact carried by
 * {@link SegmentStarted} and {@link ClipFinalized}, not a connection state (contract v0.2,
 * decision 1).
 */
public enum StreamState {
    /** Start requested, no frame yet. */
    CONNECTING,
    /** Frames are arriving. */
    ONLINE,
    /** No frames and no session; the stream manager owns recovery. */
    OFFLINE,
    /** Recovery attempted or in progress after a drop or stall. */
    RECONNECTING,
    /** Unrecoverable configuration or native failure; shows the detail. */
    ERROR
}
