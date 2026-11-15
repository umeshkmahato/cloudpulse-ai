package com.cloudpulse.remediation.k8s;

/**
 * The controller's ONLY window into Kubernetes. The HPA owns replica counts, so the only lever is the HPA's minReplicas
 * (the "capacity floor"): raising it guarantees capacity without fighting the autoscaler, and it is restored after recovery.
 */
public interface KubernetesScaler {
    /** desired = deployment spec.replicas, ready = ready pods, floor = HPA minReplicas. */
    record State(int desiredReplicas, int readyReplicas, int floor) {}

    State state(String namespace, String deployment) throws Exception;

    /** Sets the HPA minReplicas and returns the state read back afterwards. */
    State setCapacityFloor(String namespace, String deployment, int floor) throws Exception;

    String mode();
}
