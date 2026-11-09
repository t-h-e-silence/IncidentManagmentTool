package audit.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        Kind of thing that changed: `INCIDENT`, or `COMMENT` for COMMENT_ADDED.""")
public enum AuditEntityType {
    INCIDENT,
    COMMENT
}
