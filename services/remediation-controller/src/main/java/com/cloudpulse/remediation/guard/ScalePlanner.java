package com.cloudpulse.remediation.guard;

import com.cloudpulse.remediation.RemediationProperties;
import com.cloudpulse.remediation.RemediationProperties.Target;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * The only place that decides WHAT may be scaled and BY HOW MUCH. Pure and exhaustively unit-tested.
 * Nothing outside the configured allow-list can be planned, and bounds are hard limits.
 */
@Component
public class ScalePlanner {
    private static final Pattern DEPLOYMENT_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");

    public sealed interface Plan permits Allowed, Blocked {}
    public record Allowed(String deployment, int from, int to, String note) implements Plan {}
    public record Blocked(String reason) implements Plan {}

    private final RemediationProperties props;

    public ScalePlanner(RemediationProperties props) {
        this.props = props;
    }

    public boolean isAllowListed(String service) {
        return service != null && DEPLOYMENT_NAME.matcher(service).matches() && props.targets().containsKey(service);
    }

    public Target target(String service) { return props.targets().get(service); }

    /** Plan a scale-out from {@code current} replicas. */
    public Plan plan(String service, int current) {
        if (!isAllowListed(service)) return new Blocked("'" + service + "' is not in the remediation allow-list; the controller can never touch it");
        Target t = props.targets().get(service);
        if (current < t.minReplicas()) current = t.minReplicas();
        if (current >= t.maxReplicas()) {
            return new Blocked(service + " is already at its maximum of " + t.maxReplicas() + " replicas: scaling cannot help, investigate other causes (e.g. database connections)");
        }
        int to = Math.min(t.maxReplicas(), current + t.maxStep());
        return new Allowed(service, current, to, "bounds ok: " + current + " -> " + to + " replicas (max " + t.maxReplicas() + ", step <= " + t.maxStep() + ")");
    }
}
