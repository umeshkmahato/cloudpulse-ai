package com.cloudpulse.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "cloudpulse.sink")
public record SinkProperties(String target, String bigqueryDataset, boolean consumerEnabled, List<String> subscriptions) {}
