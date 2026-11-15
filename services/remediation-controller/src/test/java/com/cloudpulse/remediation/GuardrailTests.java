package com.cloudpulse.remediation;

import com.cloudpulse.remediation.guard.AutoApprovalPolicy;
import com.cloudpulse.remediation.guard.AutoApprovalPolicy.Facts;
import com.cloudpulse.remediation.guard.ScalePlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailTests {
    static RemediationProperties props(boolean auto, String token) {
        return new RemediationProperties("dry-run", 10_000, "http://x", "cloudpulse", new RemediationProperties.Jwt("i", "a", "http://x/jwks"), 15, 5, 10, 3, 85,
                Map.of("order-service", new RemediationProperties.Target(2, 6, 2), "operations-service", new RemediationProperties.Target(1, 3, 1)),
                new RemediationProperties.Policy(auto, "scale-out-v1", List.of("order-service"), 80), new RemediationProperties.K8s("", "", ""));
    }

    final ScalePlanner planner = new ScalePlanner(props(false, ""));

    @Test
    void plansBoundedStepAndClampsToTheMaximum() {
        assertThat(planner.plan("order-service", 2)).isEqualTo(new ScalePlanner.Allowed("order-service", 2, 4, "bounds ok: 2 -> 4 replicas (max 6, step <= 2)"));
        assertThat(((ScalePlanner.Allowed) planner.plan("order-service", 5)).to()).isEqualTo(6);          // 5+2 clamped to max 6
        assertThat(((ScalePlanner.Allowed) planner.plan("operations-service", 1)).to()).isEqualTo(2);     // step 1
        assertThat(((ScalePlanner.Allowed) planner.plan("order-service", 0)).from()).isEqualTo(2);        // below min lifted to min
    }

    @Test
    void refusesWhenAlreadyAtMaximumBecauseMoreReplicasCannotHelp() {
        var b = (ScalePlanner.Blocked) planner.plan("order-service", 6);
        assertThat(b.reason()).contains("maximum of 6").contains("investigate other causes");
    }

    @Test
    void anythingOutsideTheAllowListIsDeniedIncludingInjectionAttempts() {
        for (String evil : List.of("analytics-sink", "postgres", "kube-system", "../../secrets", "order-service; rm -rf /", "order-service/scale?x=1", "ORDER-SERVICE", "", " order-service")) {
            assertThat(planner.isAllowListed(evil)).as(evil).isFalse();
            assertThat(planner.plan(evil, 2)).isInstanceOf(ScalePlanner.Blocked.class);
        }
        assertThat(planner.isAllowListed(null)).isFalse();
    }

    @Test
    void automatedPolicyIsOffByDefaultAndNarrowWhenOn() {
        var off = new AutoApprovalPolicy(props(false, ""));
        assertThat(off.evaluate(new Facts("order-service", "DETECTED", 99, false)).approved()).isFalse();

        var on = new AutoApprovalPolicy(props(true, ""));
        assertThat(on.evaluate(new Facts("order-service", "DETECTED", 95, false)).approved()).isTrue();
        assertThat(on.evaluate(new Facts("order-service", "FORECAST", 95, false)).reason()).contains("forecasts");
        assertThat(on.evaluate(new Facts("order-service", "DETECTED", 60, false)).reason()).contains("below the policy threshold");
        assertThat(on.evaluate(new Facts("inventory-service", "DETECTED", 95, false)).reason()).contains("does not cover");
        assertThat(on.evaluate(new Facts("order-service", "DETECTED", 95, true)).reason()).contains("database connections are saturated");
    }
}
