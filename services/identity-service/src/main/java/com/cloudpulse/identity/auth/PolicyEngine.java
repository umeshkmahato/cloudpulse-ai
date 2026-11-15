package com.cloudpulse.identity.auth;

import com.cloudpulse.identity.IdentityProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Authorization decision for the gateway: (method, path, user's highest role) -> ALLOW or DENY. First matching rule wins; no match = DENY.
 * Paths are sanity-checked first so encoded/dot-segment tricks cannot slip past the regexes.
 */
@Component
public class PolicyEngine {
    public enum Decision { ALLOW, DENY_ROLE, DENY_NO_RULE, DENY_BAD_PATH }

    private record CompiledRule(String method, Pattern path, Role role) {}

    private final List<CompiledRule> rules;

    public PolicyEngine(IdentityProperties props) {
        this.rules = props.policy().stream().map(r -> new CompiledRule(r.method().toUpperCase(), Pattern.compile(r.path()), Role.valueOf(r.role()))).toList();
    }

    public Decision decide(String method, String uri, Role userRole) {
        if (uri == null || !saneUri(uri)) return Decision.DENY_BAD_PATH;
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        for (CompiledRule r : rules) {
            if (r.method().equals(method.toUpperCase()) && r.path().matcher(path).matches()) {
                return userRole.atLeast(r.role()) ? Decision.ALLOW : Decision.DENY_ROLE;
            }
        }
        return Decision.DENY_NO_RULE;
    }

    /** Reject anything that could be normalised differently by the gateway and the backend. */
    static boolean saneUri(String uri) {
        if (!uri.startsWith("/") || uri.length() > 2000) return false;
        String lower = uri.toLowerCase();
        if (uri.contains("..") || uri.contains("//") || uri.contains("\\") || lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")) return false;
        for (char c : uri.toCharArray()) if (c < 0x21 || c > 0x7e) return false;      // control chars, spaces, non-ASCII
        return true;
    }
}
