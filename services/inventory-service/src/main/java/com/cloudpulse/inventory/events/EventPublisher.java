package com.cloudpulse.inventory.events;

import java.util.Map;

/** Port for publishing; the Pub/Sub adapter is swappable (emulator vs real GCP is configuration only). */
public interface EventPublisher {
    /** Blocks until the broker acknowledges the message or throws. */
    void publish(String topic, String jsonPayload, Map<String, String> attributes) throws Exception;
}
