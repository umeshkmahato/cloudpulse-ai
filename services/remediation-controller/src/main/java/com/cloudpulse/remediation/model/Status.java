package com.cloudpulse.remediation.model;

public enum Status {
    PROPOSED, APPROVED, EXECUTING, MONITORING, RECOVERED, NOT_RECOVERED, REJECTED, EXPIRED, BLOCKED, FAILED;

    public boolean terminal() {
        return this == RECOVERED || this == NOT_RECOVERED || this == REJECTED || this == EXPIRED || this == BLOCKED || this == FAILED;
    }
}
