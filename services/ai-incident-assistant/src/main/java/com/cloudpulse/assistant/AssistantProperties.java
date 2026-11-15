package com.cloudpulse.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudpulse.assistant")
public record AssistantProperties(String operationsBaseUrl, int timeoutMs, String narrativeProvider, Vertex vertex) {
    public record Vertex(String projectId, String location, String model) {}
}
