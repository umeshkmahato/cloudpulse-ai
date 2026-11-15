package com.cloudpulse.remediation.guard;

import com.cloudpulse.remediation.RemediationProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Explicit, auditable policy for approving WITHOUT a human. Off by default. Even when on, it only fires for a narrow case
 * and never when scaling could make things worse (database connection saturation).
 */
@Component
public class AutoApprovalPolicy {
    public record Facts(String service, String incidentKind, double riskScore, boolean dbSaturated) {}
    public record Decision(boolean approved, String reason) {}

    private final RemediationProperties props;

    public AutoApprovalPolicy(RemediationProperties props) {
        this.props = props;
    }

    public Decision evaluate(Facts f) {
        var p = props.policy();
        if (!p.autoApprove()) return new Decision(false, "automated policy is disabled: human approval required");
        if (!p.services().contains(f.service())) return new Decision(false, "policy " + p.name() + " does not cover " + f.service());
        if (!"DETECTED".equals(f.incidentKind())) return new Decision(false, "policy only covers detected anomalies, not forecasts");
        if (f.riskScore() < p.minRisk()) return new Decision(false, "risk " + f.riskScore() + " is below the policy threshold " + p.minRisk());
        if (f.dbSaturated()) return new Decision(false, "database connections are saturated: scaling out may worsen it, human approval required");
        return new Decision(true, "matches policy " + p.name());
    }

    public Optional<String> policyName(Decision d) { return d.approved() ? Optional.of(props.policy().name()) : Optional.empty(); }
}
