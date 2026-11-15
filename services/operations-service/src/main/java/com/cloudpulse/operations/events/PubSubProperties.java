package com.cloudpulse.operations.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudpulse.pubsub")
public record PubSubProperties(String projectId, String emulatorHost, String metricsTopic,
                               String metricsSubscription, String alertsTopic, boolean consumerEnabled) {
    public boolean useEmulator() {
        return emulatorHost != null && !emulatorHost.isBlank();
    }
}
