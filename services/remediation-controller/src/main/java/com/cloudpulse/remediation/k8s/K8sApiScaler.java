package com.cloudpulse.remediation.k8s;

import com.cloudpulse.remediation.RemediationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Talks to the Kubernetes API with the pod's own ServiceAccount token. Three calls only:
 *   GET   deployments/{name}  and  horizontalpodautoscalers/{name}      (read state)
 *   PATCH horizontalpodautoscalers/{name}   {"spec":{"minReplicas":N}}  (raise / restore the capacity floor)
 * RBAC (kubernetes/base/rbac.yaml) limits the PATCH to three named HPAs, so even a bug here cannot change anything else.
 */
@Component
@ConditionalOnProperty(name = "cloudpulse.remediation.mode", havingValue = "enforce")
public class K8sApiScaler implements KubernetesScaler {
    private static final Pattern NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");

    private final RemediationProperties.K8s cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public K8sApiScaler(RemediationProperties props, ObjectMapper mapper) throws Exception {
        this.cfg = props.k8s();
        this.mapper = mapper;
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (var in = new FileInputStream(cfg.caPath())) {
            ks.setCertificateEntry("k8s-ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        this.http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public State state(String ns, String name) throws Exception {
        JsonNode d = send(HttpRequest.newBuilder(uri("apps/v1", "deployments", ns, name)).GET());
        JsonNode h = send(HttpRequest.newBuilder(uri("autoscaling/v2", "horizontalpodautoscalers", ns, name)).GET());
        return new State(d.path("spec").path("replicas").asInt(), d.path("status").path("readyReplicas").asInt(0), h.path("spec").path("minReplicas").asInt(1));
    }

    @Override
    public State setCapacityFloor(String ns, String name, int floor) throws Exception {
        JsonNode h = send(HttpRequest.newBuilder(uri("autoscaling/v2", "horizontalpodautoscalers", ns, name)).GET());
        int max = h.path("spec").path("maxReplicas").asInt();
        if (floor < 1 || floor > max) throw new IllegalArgumentException("capacity floor " + floor + " outside 1.." + max + " (the HPA maximum)");
        send(HttpRequest.newBuilder(uri("autoscaling/v2", "horizontalpodautoscalers", ns, name))
                .header("Content-Type", "application/merge-patch+json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"spec\":{\"minReplicas\":" + floor + "}}")));
        return state(ns, name);
    }

    @Override
    public String mode() { return "enforce"; }

    private URI uri(String apiVersion, String resource, String ns, String name) {
        if (!NAME.matcher(ns).matches() || !NAME.matcher(name).matches()) throw new IllegalArgumentException("invalid namespace or deployment name");
        return URI.create(cfg.apiUrl() + "/apis/" + apiVersion + "/namespaces/" + ns + "/" + resource + "/" + name);
    }

    private JsonNode send(HttpRequest.Builder b) throws Exception {
        String token = Files.readString(Path.of(cfg.tokenPath())).trim();   // re-read: projected tokens rotate
        HttpResponse<String> res = http.send(b.header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("Kubernetes API returned " + res.statusCode() + ": " + res.body().substring(0, Math.min(200, res.body().length())));
        return mapper.readTree(res.body());
    }
}
