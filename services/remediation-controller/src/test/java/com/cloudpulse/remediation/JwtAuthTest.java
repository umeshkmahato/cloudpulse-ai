package com.cloudpulse.remediation;

import com.cloudpulse.remediation.api.JwtAuth;
import com.cloudpulse.remediation.api.JwtAuth.Role;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAuthTest {
    static RSAKey key, attackerKey;
    static JwtAuth auth;

    @BeforeAll
    static void init() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        attackerKey = new RSAKeyGenerator(2048).keyID("k1").generate();     // same kid on purpose
        auth = new JwtAuth(new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())), "cloudpulse-identity", "cloudpulse-api");
    }

    static String token(RSAKey signer, String iss, String aud, long expOffsetSec, List<String> roles) {
        try { return sign(signer, iss, aud, expOffsetSec, roles); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String sign(RSAKey signer, String iss, String aud, long expOffsetSec, List<String> roles) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), new JWTClaimsSet.Builder().subject("alice@example.com")
                .issuer(iss).audience(aud).claim("roles", roles).expirationTime(new Date(System.currentTimeMillis() + expOffsetSec * 1000)).build());
        jwt.sign(new RSASSASigner(signer));
        return "Bearer " + jwt.serialize();
    }

    static int status(Runnable r) {
        try { r.run(); return 200; } catch (ResponseStatusException e) { return e.getStatusCode().value(); }
    }

    @Test
    void validOperatorTokenYieldsAVerifiedIdentityAndRole() throws Exception {
        var p = auth.require(token(key, "cloudpulse-identity", "cloudpulse-api", 300, List.of("OPERATOR")), Role.OPERATOR);
        assertThat(p.name()).isEqualTo("alice@example.com");
        assertThat(p.highest()).isEqualTo(Role.OPERATOR);
    }

    @Test
    void rolesAreEnforced() throws Exception {
        String viewer = token(key, "cloudpulse-identity", "cloudpulse-api", 300, List.of("VIEWER"));
        String operator = token(key, "cloudpulse-identity", "cloudpulse-api", 300, List.of("OPERATOR"));
        String bogus = token(key, "cloudpulse-identity", "cloudpulse-api", 300, List.of("SUPERUSER"));
        assertThat(status(() -> auth.require(viewer, Role.OPERATOR))).isEqualTo(403);
        assertThat(status(() -> auth.require(operator, Role.ADMIN))).isEqualTo(403);
        assertThat(status(() -> auth.require(bogus, Role.OPERATOR))).isEqualTo(403);        // unknown roles grant nothing
        assertThat(status(() -> auth.require(operator, Role.OPERATOR))).isEqualTo(200);
    }

    @Test
    void everyKindOfInvalidTokenIs401() throws Exception {
        assertThat(status(() -> auth.require(null, Role.VIEWER))).isEqualTo(401);
        assertThat(status(() -> auth.require("Basic abc", Role.VIEWER))).isEqualTo(401);
        assertThat(status(() -> auth.require("Bearer garbage", Role.VIEWER))).isEqualTo(401);
        assertThat(status(() -> auth.require(token(attackerKey, "cloudpulse-identity", "cloudpulse-api", 300, List.of("ADMIN")), Role.VIEWER))).as("forged signature").isEqualTo(401);
        assertThat(status(() -> auth.require(token(key, "evil-issuer", "cloudpulse-api", 300, List.of("ADMIN")), Role.VIEWER))).as("wrong issuer").isEqualTo(401);
        assertThat(status(() -> auth.require(token(key, "cloudpulse-identity", "other-api", 300, List.of("ADMIN")), Role.VIEWER))).as("wrong audience").isEqualTo(401);
        assertThat(status(() -> auth.require(token(key, "cloudpulse-identity", "cloudpulse-api", -3600, List.of("ADMIN")), Role.VIEWER))).as("expired").isEqualTo(401);

        SignedJWT hs = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject("m").issuer("cloudpulse-identity").audience("cloudpulse-api")
                .claim("roles", List.of("ADMIN")).expirationTime(new Date(System.currentTimeMillis() + 60000)).build());
        hs.sign(new MACSigner("0123456789abcdef0123456789abcdef".getBytes()));
        assertThat(status(() -> auth.require("Bearer " + hs.serialize(), Role.VIEWER))).as("HS256 confusion").isEqualTo(401);
        String none = new PlainJWT(new JWTClaimsSet.Builder().subject("m").issuer("cloudpulse-identity").audience("cloudpulse-api").claim("roles", List.of("ADMIN")).build()).serialize();
        assertThat(status(() -> auth.require("Bearer " + none, Role.VIEWER))).as("alg none").isEqualTo(401);
    }
}
