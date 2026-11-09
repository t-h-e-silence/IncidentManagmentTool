package audit.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What happened, one per incident event type (ADR D14).
 */
@Schema(description = """
        What happened: `INCIDENT_CREATED`, `DETAILS_UPDATED` (title / description), `ESCALATED` (severity raised, maybe handed over), `DE_ESCALATED` (severity lowered), `STATUS_CHANGED`, `COMMENT_ADDED`.""")
public enum AuditAction {
    INCIDENT_CREATED,
    COMMENT_ADDED,
    STATUS_CHANGED,
    DETAILS_UPDATED,
    ESCALATED,
    DE_ESCALATED
}
