package com.cloudpulse.order.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudpulse.pubsub")
public record PubSubProperties(String projectId, String emulatorHost, String ordersTopic,
                               String inventorySubscription, boolean consumerEnabled) {
    public boolean useEmulator() {
        return emulatorHost != null && !emulatorHost.isBlank();
    }
}
