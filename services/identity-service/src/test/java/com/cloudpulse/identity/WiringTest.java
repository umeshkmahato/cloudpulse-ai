package com.cloudpulse.identity;

import com.cloudpulse.identity.api.AuthController;
import com.cloudpulse.identity.auth.LoginGuard;
import com.cloudpulse.identity.auth.PolicyEngine;
import com.cloudpulse.identity.auth.TokenService;
import com.cloudpulse.identity.auth.UserDirectory;
import com.cloudpulse.identity.store.AuthAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Catches "works in unit tests, fails to start" bugs (e.g. ambiguous constructors) without needing a database. */
class WiringTest {
    @Test
    void theApplicationContextStartsWithRealBeanWiring() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues("cloudpulse.identity.issuer=i", "cloudpulse.identity.audience=a", "cloudpulse.identity.token-ttl-minutes=5", "cloudpulse.identity.cookie-name=c",
                        "cloudpulse.identity.max-failures=5", "cloudpulse.identity.max-failures-per-ip=30", "cloudpulse.identity.lockout-minutes=5", "cloudpulse.identity.users-json=[]",
                        "cloudpulse.identity.policy[0].method=GET", "cloudpulse.identity.policy[0].path=^/x$", "cloudpulse.identity.policy[0].role=VIEWER")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(AuthController.class).hasSingleBean(LoginGuard.class).hasSingleBean(TokenService.class);
                });
    }

    @EnableConfigurationProperties(IdentityProperties.class)
    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.Import({AuthController.class, TokenService.class, UserDirectory.class, LoginGuard.class, PolicyEngine.class})
    static class Config {
        @org.springframework.context.annotation.Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @org.springframework.context.annotation.Bean AuthAudit audit() { return Mockito.mock(AuthAudit.class); }
    }
}
