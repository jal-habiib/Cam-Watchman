package com.cctv.app.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cctv.contract.EventBus;
import com.cctv.contract.StreamState;
import com.cctv.contract.StreamStatusEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimpleEventBusTest {

    @Test
    void deliversOnlyMatchingTypesInSubscriptionOrder() {
        SimpleEventBus bus = new SimpleEventBus();
        List<String> seen = new ArrayList<>();
        bus.subscribe(StreamStatusEvent.class, s -> seen.add("first:" + s.cameraId()));
        bus.subscribe(StreamStatusEvent.class, s -> seen.add("second:" + s.cameraId()));

        bus.publish(StreamStatusEvent.now(1, StreamState.ONLINE, "ok"));
        bus.publish("not an event record");

        assertEquals(List.of("first:1", "second:1"), seen);
        assertEquals(2, bus.listenerCount());
    }

    @Test
    void aThrowingListenerDoesNotBreakTheOthers() {
        SimpleEventBus bus = new SimpleEventBus();
        List<Long> seen = new ArrayList<>();
        bus.subscribe(StreamStatusEvent.class, s -> {
            throw new IllegalStateException("listener defect");
        });
        bus.subscribe(StreamStatusEvent.class, s -> seen.add(s.cameraId()));

        bus.publish(StreamStatusEvent.now(7, StreamState.OFFLINE, "gone"));

        assertEquals(List.of(7L), seen);
    }

    @Test
    void closingASubscriptionUnregistersIt() {
        SimpleEventBus bus = new SimpleEventBus();
        List<String> seen = new ArrayList<>();
        EventBus.Subscription subscription = bus.subscribe(StreamStatusEvent.class,
                s -> seen.add("closed:" + s.cameraId()));
        bus.subscribe(StreamStatusEvent.class, s -> seen.add("kept:" + s.cameraId()));

        subscription.close();
        subscription.close(); // idempotent
        bus.publish(StreamStatusEvent.now(3, StreamState.ONLINE, "ok"));

        assertEquals(List.of("kept:3"), seen);
        assertEquals(1, bus.listenerCount());
    }

    @Test
    void rejectsNullSubscriptionsAndIgnoresNullEvents() {
        SimpleEventBus bus = new SimpleEventBus();
        assertThrows(IllegalArgumentException.class, () -> bus.subscribe(null, e -> { }));
        assertThrows(IllegalArgumentException.class, () -> bus.subscribe(String.class, null));
        bus.publish(null);
        assertTrue(bus.listenerCount() == 0);
    }
}
