package com.cctv.app.event;

import com.cctv.contract.EventBus;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronous, thread-safe {@link EventBus} for the MVP.
 *
 * <p>Delivery stays on the publisher's thread on purpose: it keeps ordering for one camera and
 * costs nothing. The contract rule is that listeners only enqueue (or hop to the FX thread), so a
 * listener that does I/O is a defect, not a reason to add a thread here. A listener that throws is
 * logged and isolated - it never breaks the publisher or the other listeners.
 *
 * <p>Subscriptions can be closed at shutdown (contract v0.2 decision 2); closing twice is a no-op
 * and listeners added after a close are unaffected.
 */
public final class SimpleEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(SimpleEventBus.class);

    private final class Registration<T> implements Subscription {
        private final Class<T> type;
        private final Consumer<? super T> listener;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(Class<T> type, Consumer<? super T> listener) {
            this.type = type;
            this.listener = listener;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                subscriptions.remove(this);
            }
        }

        private void deliver(Object event) {
            try {
                listener.accept(type.cast(event));
            } catch (RuntimeException e) {
                log.warn("event listener for {} failed: {}", type.getSimpleName(), e.toString(), e);
            }
        }
    }

    private final List<Registration<?>> subscriptions = new CopyOnWriteArrayList<>();

    @Override
    public <T> Subscription subscribe(Class<T> type, Consumer<? super T> listener) {
        if (type == null || listener == null) {
            throw new IllegalArgumentException("type and listener are required");
        }
        Registration<T> registration = new Registration<>(type, listener);
        subscriptions.add(registration);
        return registration;
    }

    @Override
    public void publish(Object event) {
        if (event == null) {
            return;
        }
        for (Registration<?> registration : subscriptions) {
            if (registration.type.isInstance(event)) {
                registration.deliver(event);
            }
        }
    }

    /** Number of registered listeners; diagnostics only. */
    public int listenerCount() {
        return subscriptions.size();
    }
}
