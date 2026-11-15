package com.cloudpulse.identity.api;

import com.cloudpulse.identity.IdentityProperties;
import com.cloudpulse.identity.auth.*;
import com.cloudpulse.identity.auth.PolicyEngine.Decision;
import com.cloudpulse.identity.store.AuthAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/auth")
@Tag(name = "Identity")
public class AuthController {
    public record LoginRequest(@NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 200) String password) {}

    private final IdentityProperties props;
    private final TokenService tokens;
    private final UserDirectory users;
    private final LoginGuard guard;
    private final PolicyEngine policy;
    private final AuthAudit audit;

    public AuthController(IdentityProperties props, TokenService tokens, UserDirectory users, LoginGuard guard, PolicyEngine policy, AuthAudit audit) {
        this.props = props;
        this.tokens = tokens;
        this.users = users;
        this.guard = guard;
        this.policy = policy;
        this.audit = audit;
    }

    @PostMapping("/login")
    @Operation(summary = "Log in. Sets an HttpOnly, SameSite=Strict session cookie holding a short-lived signed JWT")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String ip = clientIp(http);
        long wait = Math.max(guard.retryAfterSeconds("u:" + req.username()), guard.retryAfterSeconds("ip:" + ip));
        if (wait > 0) {
            audit.record("LOGIN_LOCKED", req.username(), ip, "retry after " + wait + "s");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", String.valueOf(wait)).body(Map.of("error", "too many attempts, try again later"));
        }
        var user = users.authenticate(req.username(), req.password());
        if (user.isEmpty()) {
            guard.failure("u:" + req.username(), props.maxFailures());
            guard.failure("ip:" + ip, props.maxFailuresPerIp());
            audit.record("LOGIN_FAILED", req.username(), ip, "invalid credentials");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid credentials"));   // same message for unknown user and wrong password
        }
        guard.success("u:" + req.username());
        guard.success("ip:" + ip);
        String jwt = tokens.issue(user.get().username(), user.get().roles());
        audit.record("LOGIN_OK", user.get().username(), ip, "roles=" + tokens.roleList(user.get().roles()));
        ResponseCookie cookie = ResponseCookie.from(props.cookieName(), jwt).httpOnly(true).secure(props.cookieSecure()).sameSite("Strict").path("/")
                .maxAge(Duration.ofMinutes(props.tokenTtlMinutes())).build();
        return ResponseEntity.ok().header("Set-Cookie", cookie.toString())
                .body(Map.of("username", user.get().username(), "roles", user.get().roles().stream().map(Enum::name).sorted().toList()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest http) {
        tokenFrom(http).ifPresent(t -> { try { audit.record("LOGOUT", tokens.verify(t).subject(), clientIp(http), null); } catch (Exception ignored) { } });
        ResponseCookie cleared = ResponseCookie.from(props.cookieName(), "").httpOnly(true).secure(props.cookieSecure()).sameSite("Strict").path("/").maxAge(0).build();
        return ResponseEntity.ok().header("Set-Cookie", cleared.toString()).body(Map.of("status", "logged out"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest http) {
        var t = tokenFrom(http);
        if (t.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "not authenticated"));
        try {
            var c = tokens.verify(t.get());
            return ResponseEntity.ok(Map.of("username", c.subject(), "roles", c.roles().stream().map(Enum::name).sorted().toList(), "expiresAt", c.expiresAt().toString()));
        } catch (TokenService.InvalidToken e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "not authenticated"));
        }
    }

    @GetMapping(value = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Public signing keys so services can verify tokens themselves (defence in depth)")
    public String jwks() { return tokens.jwksJson(); }

    /** nginx auth_request target. 204 = allowed (identity headers returned), 401 = not authenticated, 403 = authenticated but not allowed. */
    @GetMapping("/verify")
    public ResponseEntity<Void> verify(HttpServletRequest http, @RequestHeader(value = "X-Original-URI", required = false) String uri,
                                       @RequestHeader(value = "X-Original-Method", required = false) String method,
                                       @RequestHeader(value = "X-CSRF", required = false) String csrf) {
        var token = tokenFrom(http);
        if (token.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        TokenService.Claims claims;
        try {
            claims = tokens.verify(token.get());
        } catch (TokenService.InvalidToken e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String m = method == null ? "GET" : method.toUpperCase();
        Decision d = policy.decide(m, uri, claims.highest());
        if (d != Decision.ALLOW) {
            audit.record("ACCESS_DENIED", claims.subject(), clientIp(http), d + " " + m + " " + (uri == null ? "" : uri.substring(0, Math.min(uri.length(), 120))));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        boolean safe = Set.of("GET", "HEAD", "OPTIONS").contains(m);
        if (!safe && !"1".equals(csrf)) {            // SameSite=Strict + a custom header the browser will not add cross-site: CSRF defence in depth
            audit.record("CSRF_REJECTED", claims.subject(), clientIp(http), m + " " + uri);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.noContent().header("X-Auth-User", claims.subject()).header("X-Auth-Roles", tokens.roleList(claims.roles())).header("X-Auth-Token", token.get()).build();
    }

    @GetMapping("/audit")
    @Operation(summary = "Recent security audit events (ADMIN only, enforced by the gateway policy default-deny; reachable internally)")
    public Object auditTrail(@RequestParam(defaultValue = "50") int limit) { return audit.recent(Math.min(Math.max(limit, 1), 200)); }

    private java.util.Optional<String> tokenFrom(HttpServletRequest http) {
        String h = http.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) return java.util.Optional.of(h.substring(7));
        if (http.getCookies() != null) for (Cookie c : http.getCookies()) if (c.getName().equals(props.cookieName())) return java.util.Optional.of(c.getValue());
        return java.util.Optional.empty();
    }

    private static String clientIp(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        return xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : http.getRemoteAddr();
    }
}
