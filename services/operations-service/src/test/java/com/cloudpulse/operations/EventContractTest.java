package com.cloudpulse.operations;

import com.cloudpulse.operations.events.EventEnvelope;
import com.cloudpulse.operations.events.MetricSampleData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventContractTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonSchema schema() throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("../../architecture/event-schemas/metric.sample.v1.schema.json"))) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
        }
    }

    @Test
    void metricSampleEnvelopeMatchesSchema() throws Exception {
        var env = new EventEnvelope<>(UUID.randomUUID().toString(), "metric.sample", 1, Instant.now(), "corr-1",
                EventEnvelope.SOURCE, new MetricSampleData("order-service", "cpu_pct", 55.0, "pct", true, Instant.now()));
        JsonNode json = mapper.readTree(mapper.writeValueAsString(env));
        assertThat(schema().validate(json)).isEmpty();
    }

    @Test
    void alertRaisedEventMatchesSchema() throws Exception {
        var data = new com.cloudpulse.operations.detect.AlertPublisher.AlertData("id-1", "order-service", "DETECTED", "CRITICAL", 92,
                "OPENED", "Anomaly detected", java.util.List.of("inventory-service"), true);
        var env = new EventEnvelope<>(UUID.randomUUID().toString(), "alert.raised", 1, Instant.now(), "inc-abc", EventEnvelope.SOURCE, data);
        JsonNode json = mapper.readTree(mapper.writeValueAsString(env));
        try (InputStream in = Files.newInputStream(Path.of("../../architecture/event-schemas/alert.raised.v1.schema.json"))) {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
            assertThat(schema.validate(json)).isEmpty();
            ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("data")).put("kind", "GUESS");
            assertThat(schema.validate(json)).isNotEmpty();
        }
    }

    @Test
    void schemaRejectsUnknownMetricAndMissingSyntheticLabel() throws Exception {
        JsonNode bad = mapper.readTree("""
            {"eventId":"e","eventType":"metric.sample","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z","correlationId":"c",
             "source":"operations-service","data":{"service":"s","metric":"bogus","value":1}}""");
        assertThat(schema().validate(bad)).hasSizeGreaterThanOrEqualTo(2);
    }
}
