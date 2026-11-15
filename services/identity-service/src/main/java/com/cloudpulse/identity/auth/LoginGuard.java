package com.cloudpulse.identity.auth;

import com.cloudpulse.identity.IdentityProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Brute-force protection: after N consecutive failures a key (username or source IP) is locked for a while. */
@Component
public class LoginGuard {
    private record State(int failures, Instant lockedUntil) {}

    private final IdentityProperties props;
    private final Clock clock;
    private final Map<String, State> state = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public LoginGuard(IdentityProperties props) {
        this(props, Clock.systemUTC());
    }

    public LoginGuard(IdentityProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /** Seconds until the key may try again, or 0 if allowed. */
    public long retryAfterSeconds(String key) {
        State s = state.get(key);
        if (s == null || s.lockedUntil() == null) return 0;
        long left = Duration.between(clock.instant(), s.lockedUntil()).getSeconds();
        if (left <= 0) { state.remove(key); return 0; }
        return left;
    }

    public void failure(String key, int limit) {
        state.merge(key, new State(1, null), (old, n) -> {
            int f = old.failures() + 1;
            return f >= limit ? new State(f, clock.instant().plus(Duration.ofMinutes(props.lockoutMinutes()))) : new State(f, old.lockedUntil());
        });
    }

    public void success(String key) { state.remove(key); }
}
