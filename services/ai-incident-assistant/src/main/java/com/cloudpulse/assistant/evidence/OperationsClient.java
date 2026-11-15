package com.cloudpulse.assistant.evidence;

import com.cloudpulse.assistant.AssistantProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Optional;
import org.springframework.web.client.RestClient;

/** Read-only access to the Operations API. The assistant has no write access to anything. */
@Component
public class OperationsClient {
    public static class EvidenceUnavailable extends RuntimeException {
        public EvidenceUnavailable(String path, Throwable cause) { super("Operations API unavailable for " + path, cause); }
    }

    private final RestClient http;

    public OperationsClient(AssistantProperties props) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(props.timeoutMs());
        f.setReadTimeout(props.timeoutMs());
        this.http = RestClient.builder().baseUrl(props.operationsBaseUrl()).requestFactory(f).build();
    }

    /** Like get(), but a 404 means "that thing does not exist" (empty), not "the API is down". */
    public Optional<JsonNode> getIfExists(String pathAndQuery) {
        try {
            return Optional.ofNullable(http.get().uri(pathAndQuery).retrieve().body(JsonNode.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new EvidenceUnavailable(pathAndQuery, e);
        }
    }

    public JsonNode get(String pathAndQuery) {
        try {
            JsonNode n = http.get().uri(pathAndQuery).retrieve().body(JsonNode.class);
            if (n == null) throw new IllegalStateException("empty body");
            return n;
        } catch (Exception e) {
            throw new EvidenceUnavailable(pathAndQuery, e);
        }
    }
}
