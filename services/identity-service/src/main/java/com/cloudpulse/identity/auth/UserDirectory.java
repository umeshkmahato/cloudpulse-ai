package com.cloudpulse.identity.auth;

import com.cloudpulse.identity.IdentityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.*;

/** Users come from a Secret as {username, bcrypt hash, roles}. Plaintext passwords are never stored. */
@Component
public class UserDirectory {
    private static final Logger log = LoggerFactory.getLogger(UserDirectory.class);
    public record User(String username, String bcrypt, Set<Role> roles) {}

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);
    /** Verified for unknown users so that response time does not reveal whether a username exists. */
    private static final String DUMMY_HASH = ENCODER.encode("not-a-real-password");

    private final Map<String, User> users = new HashMap<>();

    public UserDirectory(IdentityProperties props, ObjectMapper mapper) throws Exception {
        JsonNode arr = mapper.readTree(props.usersJson() == null || props.usersJson().isBlank() ? "[]" : props.usersJson());
        for (JsonNode n : arr) {
            Set<Role> roles = new HashSet<>();
            n.path("roles").forEach(r -> roles.add(Role.valueOf(r.asText())));
            String hash = n.path("bcrypt").asText();
            if (!hash.startsWith("$2")) throw new IllegalArgumentException("user " + n.path("username").asText() + " must have a bcrypt hash, not a plaintext password");
            users.put(n.path("username").asText(), new User(n.path("username").asText(), hash, roles));
        }
        if (users.isEmpty()) log.warn("no users configured (IDENTITY_USERS_JSON empty): nobody can log in");
    }

    /** Returns the user if the password matches. Constant-ish time for unknown users. */
    public Optional<User> authenticate(String username, String password) {
        User u = users.get(username);
        boolean ok = ENCODER.matches(password == null ? "" : password, u == null ? DUMMY_HASH : u.bcrypt());
        return ok && u != null ? Optional.of(u) : Optional.empty();
    }

    public int size() { return users.size(); }
}
