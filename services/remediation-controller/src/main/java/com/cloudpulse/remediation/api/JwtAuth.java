package com.cloudpulse.remediation.api;

import com.cloudpulse.remediation.RemediationProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URL;
import java.util.*;

/**
 * Verifies identity-service JWTs itself (RS256 only, issuer, audience, expiry) and extracts a VERIFIED operator identity and roles.
 * Replaces the Phase 12 shared approver token: every decision is now attributable to an authenticated person, and the controller
 * does not blindly trust the gateway.
 */
@Component
public class JwtAuth {
    public enum Role { VIEWER, OPERATOR, ADMIN; boolean atLeast(Role r) { return ordinal() >= r.ordinal(); } }
    public record Principal(String name, Role highest) {}

    private final DefaultJWTProcessor<SecurityContext> processor;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtAuth(RemediationProperties props) throws Exception {
        this(JWKSourceBuilder.create(new URL(props.jwt().jwksUrl())).retrying(true).build(), props.jwt().issuer(), props.jwt().audience());
    }

    /** Test seam: any key source. */
    public JwtAuth(JWKSource<SecurityContext> keys, String issuer, String audience) {
        processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));          // RS256 only: no HS256/none confusion
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(audience, new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("sub", "exp")));
    }

    public Principal require(String authorizationHeader, Role needed) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        Principal p;
        try {
            JWTClaimsSet c = processor.process(authorizationHeader.substring(7), null);
            Role highest = Role.VIEWER;
            for (String r : Optional.ofNullable(c.getStringListClaim("roles")).orElse(List.of())) {
                try { Role role = Role.valueOf(r); if (role.atLeast(highest)) highest = role; } catch (IllegalArgumentException ignored) { /* unknown roles grant nothing */ }
            }
            p = new Principal(c.getSubject(), highest);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token");
        }
        if (!p.highest().atLeast(needed)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "requires role " + needed);
        return p;
    }
}
