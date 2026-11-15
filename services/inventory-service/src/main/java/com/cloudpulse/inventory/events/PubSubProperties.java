package com.cloudpulse.inventory.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudpulse.pubsub")
public record PubSubProperties(String projectId, String emulatorHost, String publishTopic,
                               String ordersSubscription, boolean consumerEnabled) {
    public boolean useEmulator() {
        return emulatorHost != null && !emulatorHost.isBlank();
    }
}
