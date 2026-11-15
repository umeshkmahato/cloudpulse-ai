package com.cloudpulse.identity.auth;

/** Ordered: a higher role includes the permissions of the lower ones. */
public enum Role {
    VIEWER, OPERATOR, ADMIN;

    public boolean atLeast(Role needed) { return ordinal() >= needed.ordinal(); }
}
