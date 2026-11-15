package com.cloudpulse.remediation;

import com.cloudpulse.remediation.events.EventEnvelope;
import com.cloudpulse.remediation.events.RemediationEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void remediationEventMatchesSchemaAndRejectsUnknownActions() throws Exception {
        var data = new RemediationEvents.Data("r1", "i1", "order-service", "SCALE_DEPLOYMENT", "order-service", 2, 4, "APPROVED", "alice", "approved", true);
        var env = new EventEnvelope<>(UUID.randomUUID().toString(), "remediation.event", 1, Instant.now(), "rem-abc", EventEnvelope.SOURCE, data);
        JsonNode json = mapper.readTree(mapper.writeValueAsString(env));
        try (InputStream in = Files.newInputStream(Path.of("../../architecture/event-schemas/remediation.event.v1.schema.json"))) {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
            assertThat(schema.validate(json)).isEmpty();
            ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("data")).put("action", "RUN_SHELL");
            assertThat(schema.validate(json)).isNotEmpty();      // the contract itself forbids any action outside the catalogue
        }
    }
}
