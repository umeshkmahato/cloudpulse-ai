package com.cloudpulse.assistant.evidence;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** One retrieved fact. Everything the assistant says must point at one of these by id. */
public record Evidence(String id, String type, String source, Instant retrievedAt, String summary, JsonNode data, boolean synthetic) {}
