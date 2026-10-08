package org.example.audit.model;

public enum AuditAction {
    INCIDENT_CREATED,
    COMMENT_ADDED,
    STATUS_CHANGED,
    DETAILS_UPDATED,
    /** No longer written; kept so stored entries still load. */
    SEVERITY_CHANGED,
    /** No longer written; kept so stored entries still load. */
    REASSIGNED,
    ESCALATED,
    DE_ESCALATED,
    /** No longer written; kept so stored entries still load. */
    NOTIFICATION_REPLAYED
}
