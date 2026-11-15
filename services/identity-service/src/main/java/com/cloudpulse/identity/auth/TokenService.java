package com.cloudpulse.identity.auth;

import com.cloudpulse.identity.IdentityProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Issues and verifies RS256 JWTs. Verification pins the algorithm, issuer, audience and expiry; "alg: none" and HS256 confusion are impossible. */
@Component
public class TokenService {
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    public record Claims(String subject, Set<Role> roles, Instant expiresAt) {
        public Role highest() { return roles.stream().max(Comparator.naturalOrder()).orElse(Role.VIEWER); }
    }

    public static class InvalidToken extends RuntimeException {
        public InvalidToken(String m) { super(m); }
    }

    private final IdentityProperties props;
    private final RSAKey key;

    public TokenService(IdentityProperties props) throws Exception {
        this.props = props;
        this.key = loadKey(props.signingKeyPem());
    }

    private static RSAKey loadKey(String pem) throws Exception {
        KeyPair pair;
        if (pem == null || pem.isBlank()) {
            log.warn("IDENTITY_SIGNING_KEY not set: using an EPHEMERAL signing key (dev only; tokens become invalid on restart; do not run more than one replica)");
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            pair = g.generateKeyPair();
        } else {
            String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            KeyFactory kf = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
            pair = new KeyPair(pub, priv);
        }
        RSAKey k = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).algorithm(JWSAlgorithm.RS256).build();
        return new RSAKey.Builder(k).keyID(k.computeThumbprint().toString()).build();
    }

    public String issue(String subject, Set<Role> roles) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(props.issuer()).audience(props.audience()).subject(subject)
                    .claim("roles", roles.stream().map(Enum::name).sorted().toList())
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(props.tokenTtlMinutes() * 60L))).jwtID(UUID.randomUUID().toString()).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public Claims verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) throw new InvalidToken("unsupported algorithm");
            if (!jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))) throw new InvalidToken("bad signature");
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (!props.issuer().equals(c.getIssuer())) throw new InvalidToken("wrong issuer");
            if (c.getAudience() == null || !c.getAudience().contains(props.audience())) throw new InvalidToken("wrong audience");
            if (c.getExpirationTime() == null || c.getExpirationTime().toInstant().plusSeconds(30).isBefore(Instant.now())) throw new InvalidToken("expired");
            if (c.getSubject() == null || c.getSubject().isBlank()) throw new InvalidToken("no subject");
            Set<Role> roles = new HashSet<>();
            for (String r : Optional.ofNullable(c.getStringListClaim("roles")).orElse(List.of())) {
                try { roles.add(Role.valueOf(r)); } catch (IllegalArgumentException ignored) { /* unknown roles grant nothing */ }
            }
            return new Claims(c.getSubject(), roles, c.getExpirationTime().toInstant());
        } catch (ParseException | JOSEException e) {
            throw new InvalidToken("malformed token");
        }
    }

    public String jwksJson() { return new JWKSet(key.toPublicJWK()).toString(); }

    public String roleList(Set<Role> roles) { return roles.stream().map(Enum::name).sorted().collect(Collectors.joining(",")); }
}
