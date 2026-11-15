package com.cloudpulse.remediation.ops;

import com.cloudpulse.remediation.RemediationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/** Read-only view of incidents from the Operations service. */
@Component
public class OperationsClient {
    private final RestClient http;

    public OperationsClient(RemediationProperties props) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(4000);
        f.setReadTimeout(4000);
        this.http = RestClient.builder().baseUrl(props.operationsBaseUrl()).requestFactory(f).build();
    }

    public JsonNode openIncidents() {
        return http.get().uri("/api/v1/incidents?status=OPEN&limit=100").retrieve().body(JsonNode.class);
    }

    public Optional<JsonNode> incident(UUID id) {
        try {
            return Optional.ofNullable(http.get().uri("/api/v1/incidents/{id}", id).retrieve().body(JsonNode.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
