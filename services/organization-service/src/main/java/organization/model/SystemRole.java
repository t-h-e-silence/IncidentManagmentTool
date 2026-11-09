package organization.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Global role of a user. Team-specific rights come from {@link TeamRole}.
 */
@Schema(description = """
        Global role of a user. `USER`: any employee, reports and reads incidents. `ADMIN`: also receives the emails of teams without active members. `SYSTEM`: the system user, author of automatic actions; cannot call any endpoint.""")
public enum SystemRole {
    /** Any employee: reports and reads incidents. */
    USER,
    /** Reassigns any open incident; manages dead-lettered notifications. */
    ADMIN,
    /** The system user, author of automatic actions; cannot act through the controller. */
    SYSTEM
}
