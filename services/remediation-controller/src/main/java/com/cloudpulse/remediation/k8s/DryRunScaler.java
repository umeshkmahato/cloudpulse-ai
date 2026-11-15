package com.cloudpulse.remediation.k8s;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** dry-run mode: pretends (in memory) so the whole workflow can be exercised without a cluster. Never contacts Kubernetes. */
@Component
@ConditionalOnProperty(name = "cloudpulse.remediation.mode", havingValue = "dry-run", matchIfMissing = true)
public class DryRunScaler implements KubernetesScaler {
    private final Map<String, Integer> floors = new ConcurrentHashMap<>();

    @Override
    public State state(String namespace, String deployment) {
        int f = floors.getOrDefault(deployment, 2);
        return new State(f, f, f);
    }

    @Override
    public State setCapacityFloor(String namespace, String deployment, int floor) {
        floors.put(deployment, floor);
        return state(namespace, deployment);
    }

    @Override
    public String mode() { return "dry-run"; }
}
