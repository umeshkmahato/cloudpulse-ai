package com.cloudpulse.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "cloudpulse.identity")
public record IdentityProperties(String issuer, String audience, int tokenTtlMinutes, String cookieName, boolean cookieSecure, String signingKeyPem,
                                 String usersJson, int maxFailures, int maxFailuresPerIp, int lockoutMinutes, List<Rule> policy) {
    public record Rule(String method, String path, String role) {}
}
