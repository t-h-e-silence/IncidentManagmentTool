package org.example.common.model;

/**
 * Global role of a user. Team-specific rights come from {@link TeamRole}.
 */
public enum SystemRole {
    /** Any employee: reports and reads incidents. */
    USER,
    /** Reassigns any open incident; manages dead-lettered notifications. */
    ADMIN,
    /** The system user, author of automatic actions; cannot act through the controller. */
    SYSTEM
}
