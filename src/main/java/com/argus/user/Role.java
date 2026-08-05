package com.argus.user;

/**
 * ADMIN manages tenants, users, keys and rules.
 * ANALYST works alerts and reads events.
 * VIEWER is read-only.
 */
public enum Role {
    ADMIN,
    ANALYST,
    VIEWER
}
