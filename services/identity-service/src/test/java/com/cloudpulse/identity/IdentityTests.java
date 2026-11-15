package com.cloudpulse.identity;

import com.cloudpulse.identity.IdentityProperties.Rule;
import com.cloudpulse.identity.api.AuthController;
import com.cloudpulse.identity.auth.*;
import com.cloudpulse.identity.auth.PolicyEngine.Decision;
import com.cloudpulse.identity.store.AuthAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IdentityTests {
    static final List<Rule> POLICY = List.of(
            new Rule("GET", "^/(ops-api|orders-api|inventory-api|assistant-api|remediation-api)/api/.*", "VIEWER"),
            new Rule("POST", "^/assistant-api/api/v1/assistant/ask$", "VIEWER"),
            new Rule("POST", "^/orders-api/api/v1/orders$", "OPERATOR"),
            new Rule("POST", "^/remediation-api/api/v1/remediations/[0-9a-fA-F-]{36}/(approve|reject)$", "OPERATOR"),
            new Rule("POST", "^/remediation-api/api/v1/remediation/kill-switch$", "ADMIN"));

    static IdentityProperties props(String issuer, int ttl, String usersJson) {
        return new IdentityProperties(issuer, "cloudpulse-api", ttl, "cp_session", false, "", usersJson, 5, 30, 5, POLICY);
    }

    static String hash(String p) { return new BCryptPasswordEncoder(4).encode(p); }

    static String users() {
        return String.format("[{\"username\":\"vera\",\"bcrypt\":\"%s\",\"roles\":[\"VIEWER\"]},{\"username\":\"oscar\",\"bcrypt\":\"%s\",\"roles\":[\"OPERATOR\"]},{\"username\":\"ada\",\"bcrypt\":\"%s\",\"roles\":[\"ADMIN\"]}]",
                hash("vera-pass"), hash("oscar-pass"), hash("ada-pass"));
    }

    // ------------------------------------------------------------------ policy
    final PolicyEngine policy = new PolicyEngine(props("i", 30, "[]"));

    @Test
    void roleMatrixIsEnforcedAndDefaultIsDeny() {
        assertThat(policy.decide("GET", "/ops-api/api/v1/incidents?status=OPEN", Role.VIEWER)).isEqualTo(Decision.ALLOW);
        assertThat(policy.decide("POST", "/assistant-api/api/v1/assistant/ask", Role.VIEWER)).isEqualTo(Decision.ALLOW);
        assertThat(policy.decide("POST", "/orders-api/api/v1/orders", Role.VIEWER)).isEqualTo(Decision.DENY_ROLE);
        assertThat(policy.decide("POST", "/orders-api/api/v1/orders", Role.OPERATOR)).isEqualTo(Decision.ALLOW);
        String approve = "/remediation-api/api/v1/remediations/0f7a1c1e-0000-4000-8000-000000000001/approve";
        assertThat(policy.decide("POST", approve, Role.VIEWER)).isEqualTo(Decision.DENY_ROLE);
        assertThat(policy.decide("POST", approve, Role.OPERATOR)).isEqualTo(Decision.ALLOW);
        assertThat(policy.decide("POST", "/remediation-api/api/v1/remediation/kill-switch", Role.OPERATOR)).isEqualTo(Decision.DENY_ROLE);
        assertThat(policy.decide("POST", "/remediation-api/api/v1/remediation/kill-switch", Role.ADMIN)).isEqualTo(Decision.ALLOW);
        assertThat(policy.decide("DELETE", "/orders-api/api/v1/orders/1", Role.ADMIN)).isEqualTo(Decision.DENY_NO_RULE);
        assertThat(policy.decide("GET", "/ops-api/actuator/prometheus", Role.ADMIN)).isEqualTo(Decision.DENY_NO_RULE);      // actuator is never exposed through the gateway
        assertThat(policy.decide("GET", "/auth/audit", Role.ADMIN)).isEqualTo(Decision.DENY_NO_RULE);
    }

    @Test
    void pathTricksAreRejectedBeforeMatching() {
        for (String evil : List.of("/ops-api/api/../actuator/env", "/ops-api/api/%2e%2e/actuator", "/ops-api//api/v1/incidents", "/ops-api/api/v1/x%2Fy", "/ops-api/api/v1/a\\b",
                "/ops-api/api/v1/\u0000x", "/ops-api/api/v1/x%00", "ops-api/api/v1/x", "/ops-api/api/v1/ space", "/ops-api/api/v1/é")) {
            assertThat(policy.decide("GET", evil, Role.ADMIN)).as(evil).isEqualTo(Decision.DENY_BAD_PATH);
        }
        assertThat(policy.decide("GET", null, Role.ADMIN)).isEqualTo(Decision.DENY_BAD_PATH);
    }

    @Test
    void rolesAreOrdered() {
        assertThat(Role.ADMIN.atLeast(Role.OPERATOR)).isTrue();
        assertThat(Role.VIEWER.atLeast(Role.OPERATOR)).isFalse();
    }

    // ------------------------------------------------------------------ tokens
    @Test
    void tokensRoundTripAndRejectEveryKindOfTampering() throws Exception {
        TokenService ts = new TokenService(props("cloudpulse-identity", 30, "[]"));
        String jwt = ts.issue("oscar", Set.of(Role.OPERATOR));
        var c = ts.verify(jwt);
        assertThat(c.subject()).isEqualTo("oscar");
        assertThat(c.highest()).isEqualTo(Role.OPERATOR);

        // signed by a different key (another instance has its own ephemeral key)
        TokenService other = new TokenService(props("cloudpulse-identity", 30, "[]"));
        assertThatThrownBy(() -> ts.verify(other.issue("mallory", Set.of(Role.ADMIN)))).isInstanceOf(TokenService.InvalidToken.class).hasMessageContaining("signature");

        // payload swapped (privilege escalation attempt) keeps the old signature
        String[] p = jwt.split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"oscar\",\"roles\":[\"ADMIN\"],\"iss\":\"cloudpulse-identity\",\"aud\":\"cloudpulse-api\",\"exp\":9999999999}".getBytes());
        assertThatThrownBy(() -> ts.verify(p[0] + "." + forged + "." + p[2])).isInstanceOf(TokenService.InvalidToken.class);

        // alg=none
        String none = new PlainJWT(new JWTClaimsSet.Builder().subject("x").issuer("cloudpulse-identity").audience("cloudpulse-api").expirationTime(new Date(System.currentTimeMillis() + 60000)).build()).serialize();
        assertThatThrownBy(() -> ts.verify(none)).isInstanceOf(TokenService.InvalidToken.class);

        // HS256 signed with the public JWKS text as secret (classic algorithm-confusion attack)
        SignedJWT hs = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject("mallory").issuer("cloudpulse-identity").audience("cloudpulse-api")
                .claim("roles", List.of("ADMIN")).expirationTime(new Date(System.currentTimeMillis() + 60000)).build());
        hs.sign(new MACSigner("0123456789abcdef0123456789abcdef".getBytes()));
        assertThatThrownBy(() -> ts.verify(hs.serialize())).isInstanceOf(TokenService.InvalidToken.class).hasMessageContaining("algorithm");

        assertThatThrownBy(() -> ts.verify("not-a-jwt")).isInstanceOf(TokenService.InvalidToken.class);
    }

    @Test
    void wrongAudienceExpiredAndUnknownRolesGrantNothing() throws Exception {
        TokenService issuerA = new TokenService(props("cloudpulse-identity", -5, "[]"));
        assertThatThrownBy(() -> issuerA.verify(issuerA.issue("x", Set.of(Role.ADMIN)))).hasMessageContaining("expired");

        TokenService ts = new TokenService(props("cloudpulse-identity", 30, "[]"));
        assertThat(ts.jwksJson()).contains("\"kty\":\"RSA\"").doesNotContain("\"d\"");       // public parts only
    }

    // ------------------------------------------------------------------ users and lockout
    @Test
    void plaintextPasswordsInTheUserSecretAreRefused() {
        assertThatThrownBy(() -> new UserDirectory(props("i", 30, "[{\"username\":\"a\",\"bcrypt\":\"plain\",\"roles\":[\"ADMIN\"]}]"), new ObjectMapper()))
                .hasMessageContaining("bcrypt");
    }

    @Test
    void lockoutAfterRepeatedFailuresThenRecovers() {
        class Clk extends Clock { Instant now = Instant.parse("2026-01-01T00:00:00Z"); public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId z) { return this; } public Instant instant() { return now; } }
        Clk clk = new Clk();
        LoginGuard g = new LoginGuard(props("i", 30, "[]"), clk);
        for (int i = 0; i < 4; i++) g.failure("u:x", 5);
        assertThat(g.retryAfterSeconds("u:x")).isZero();
        g.failure("u:x", 5);
        assertThat(g.retryAfterSeconds("u:x")).isPositive();
        clk.now = clk.now.plus(Duration.ofMinutes(6));
        assertThat(g.retryAfterSeconds("u:x")).isZero();
        g.failure("u:y", 5); g.success("u:y");
        for (int i = 0; i < 4; i++) g.failure("u:y", 5);
        assertThat(g.retryAfterSeconds("u:y")).isZero();         // success reset the counter
    }

    // ------------------------------------------------------------------ HTTP behaviour
    MockMvc mvc;
    final AuthAudit audit = org.mockito.Mockito.mock(AuthAudit.class);

    @BeforeEach
    void setUp() throws Exception {
        IdentityProperties p = props("cloudpulse-identity", 30, users());
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(p, new TokenService(p), new UserDirectory(p, new ObjectMapper()), new LoginGuard(p), new PolicyEngine(p), audit)).build();
    }

    String login(String u, String pw) throws Exception {
        var res = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + u + "\",\"password\":\"" + pw + "\"}")).andExpect(status().isOk()).andReturn();
        String setCookie = res.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/");
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    @Test
    void loginSetsAHardenedCookieAndFailuresAreIndistinguishable() throws Exception {
        login("oscar", "oscar-pass");
        var wrongPw = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"oscar\",\"password\":\"nope\"}")).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        var unknown = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ghost\",\"password\":\"nope\"}")).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(wrongPw).isEqualTo(unknown);                  // no username enumeration
    }

    @Test
    void bruteForceGetsLockedOutWith429() throws Exception {
        for (int i = 0; i < 5; i++) mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ada\",\"password\":\"bad\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ada\",\"password\":\"ada-pass\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void gatewayDecisionPointEnforcesAuthenticationRolesAndCsrf() throws Exception {
        String viewer = login("vera", "vera-pass"), operator = login("oscar", "oscar-pass"), admin = login("ada", "ada-pass");
        String approve = "/remediation-api/api/v1/remediations/0f7a1c1e-0000-4000-8000-000000000001/approve";

        mvc.perform(get("/auth/verify").header("X-Original-URI", "/ops-api/api/v1/incidents").header("X-Original-Method", "GET")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/verify").cookie(new Cookie("cp_session", "garbage")).header("X-Original-URI", "/ops-api/api/v1/incidents").header("X-Original-Method", "GET")).andExpect(status().isUnauthorized());

        mvc.perform(get("/auth/verify").cookie(new Cookie("cp_session", viewer)).header("X-Original-URI", "/ops-api/api/v1/incidents").header("X-Original-Method", "GET"))
                .andExpect(status().isNoContent()).andExpect(header().string("X-Auth-User", "vera")).andExpect(header().exists("X-Auth-Token"));
        mvc.perform(get("/auth/verify").cookie(new Cookie("cp_session", viewer)).header("X-Original-URI", approve).header("X-Original-Method", "POST").header("X-CSRF", "1")).andExpect(status().isForbidden());
        mvc.perform(get("/auth/verify").cookie(new Cookie("cp_session", operator)).header("X-Original-URI", approve).header("X-Original-Method", "POST").header("X-CSRF", "1")).andExpect(status().isNoContent());
        mvc.perform(get("/auth/verify").cookie(new Cookie("cp_session", operator)).header("X-Original-URI", approve).header("X-Original-Method", "POST")).andExpect(status().isForbidden());   // no CSRF header
        mvc.perform(get("/auth/verify").cookie(new Cookie("cp_session", operator)).header("X-Original-URI", "/remediation-api/api/v1/remediation/kill-switch").header("X-Original-Method", "POST").header("X-CSRF", "1")).andExpect(status().isForbidden());
        mvc.perform(get("/auth/verify").cookie(new Cookie("cp_session", admin)).header("X-Original-URI", "/remediation-api/api/v1/remediation/kill-switch").header("X-Original-Method", "POST").header("X-CSRF", "1")).andExpect(status().isNoContent());
        mvc.perform(get("/auth/verify").cookie(new Cookie("cp_session", admin)).header("X-Original-URI", "/ops-api/actuator/env").header("X-Original-Method", "GET")).andExpect(status().isForbidden());
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.atLeastOnce()).record(org.mockito.ArgumentMatchers.eq("ACCESS_DENIED"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void bearerHeaderWorksForServiceCallersAndMeReportsIdentity() throws Exception {
        String t = login("ada", "ada-pass");
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + t)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("ada")).andExpect(jsonPath("$.roles[0]").value("ADMIN"));
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/logout")).andExpect(status().isOk()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }
}
