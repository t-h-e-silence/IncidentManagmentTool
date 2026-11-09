package incidents.model;

import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * <pre>
 * OPEN -> IN_PROGRESS -> IN_REVIEW -> RESOLVED -> CLOSED
 *              ^             |            |
 *              +--- back ----+            |
 *              +------- reopen -----------+
 * OPEN | IN_PROGRESS | IN_REVIEW -> CANCELLED
 * </pre>
 * {@code CLOSED} and {@code CANCELLED} are final. Only active incidents ({@code OPEN}, {@code IN_PROGRESS},
 * {@code IN_REVIEW}) can be commented on, edited, escalated or reassigned.
 */
@Schema(description = """
        Lifecycle step. `OPEN` → `IN_PROGRESS` (acknowledged) → `IN_REVIEW` → `RESOLVED` → `CLOSED`; `IN_REVIEW` → `IN_PROGRESS` (back to work); `RESOLVED` → `IN_PROGRESS` (reopen, needs a reason); `OPEN` / `IN_PROGRESS` / `IN_REVIEW` → `CANCELLED` (needs a reason). `CLOSED` and `CANCELLED` are final; only `OPEN`, `IN_PROGRESS` and `IN_REVIEW` incidents can be edited, escalated or commented on.""")
public enum IncidentStatus {
    OPEN,
    IN_PROGRESS,
    IN_REVIEW,
    RESOLVED,
    CLOSED,
    CANCELLED;

    /**
     * Statuses a team works on.
     */
    public static final Set<IncidentStatus> ACTIVE = Set.of(OPEN, IN_PROGRESS, IN_REVIEW);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean canMoveTo(IncidentStatus target) {
        return switch (this) {
            case OPEN -> target == IN_PROGRESS || target == CANCELLED;
            case IN_PROGRESS -> target == IN_REVIEW || target == CANCELLED;
            case IN_REVIEW -> target == IN_PROGRESS || target == RESOLVED || target == CANCELLED;
            case RESOLVED -> target == IN_PROGRESS || target == CLOSED;
            case CLOSED, CANCELLED -> false;
        };
    }
}
