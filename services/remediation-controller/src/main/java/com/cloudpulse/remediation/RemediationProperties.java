package com.cloudpulse.remediation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "cloudpulse.remediation")
public record RemediationProperties(String mode, long pollIntervalMs, String operationsBaseUrl, String namespace, Jwt jwt,
                                    int proposalTtlMinutes, int monitorWindowMinutes, int cooldownMinutes, int maxActionsPerHour,
                                    int dbSaturationBlockPct, Map<String, Target> targets, Policy policy, K8s k8s) {
    /** One allow-listed deployment and its hard bounds. */
    public record Target(int minReplicas, int maxReplicas, int maxStep) {}

    /** The explicit automated-approval policy. Disabled unless auto-approve is true. */
    public record Policy(boolean autoApprove, String name, List<String> services, double minRisk) {}

    /** Tokens are issued by identity-service and verified here too (defence in depth: the gateway is not the only check). */
    public record Jwt(String issuer, String audience, String jwksUrl) {}

    public record K8s(String apiUrl, String tokenPath, String caPath) {}

    public boolean enforce() { return "enforce".equalsIgnoreCase(mode); }
}
