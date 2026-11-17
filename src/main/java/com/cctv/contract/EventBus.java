package com.cctv.contract;

import java.util.function.Consumer;

/**
 * In-process bus between the engine (AI, recording, zones) and the app (UI, DB, alerts).
 *
 * <p>Contract v0.2 (agreed 2026-11-16, see DEEPSEEK_TO_CHATGPT_INTERFACE_REVIEW.md and
 * CHATGPT_TO_DEEPSEEK_REVIEW_AND_NEXT_HANDOFF.md).
 *
 * <p>Rules:
 * <ul>
 *   <li>Publishing is synchronous and inline on the publisher's thread. Listeners must therefore be
 *       strictly non-blocking: enqueue, or hop to the JavaFX thread with {@code Platform.runLater}.
 *       No I/O, no image conversion, no database work, no locks in a listener.</li>
 *   <li>Any thread may publish. The bus does not marshal threads.</li>
 *   <li>Every timestamp is UTC epoch milliseconds (wall clock), never local time.</li>
 *   <li>No database-generated id crosses this boundary: segments are keyed by
 *       {@code (cameraId, startUtcMs)}, zones by their own small integer id, alerts by UUID string.</li>
 * </ul>
 */
public interface EventBus {

    /**
     * Registers a listener; the returned subscription must be closed to unregister it.
     *
     * @throws IllegalArgumentException when the type or listener is null
     */
    <T> Subscription subscribe(Class<T> type, Consumer<? super T> listener);

    /** Publishes to every listener whose registered type is an instance of the event's class. */
    void publish(Object event);

    /** Handle for one registered listener. Closing twice is a no-op. */
    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }
}
