package modules.incidents.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import modules.common.model.Severity;

/**
 * @param closedAt when it was closed or cancelled
 */
public record IncidentView(
        UUID id,
        String title,
        String description,
        UUID categoryId,
        String categoryName,
        UUID teamId,
        UUID reporterId,
        Severity severity,
        IncidentStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        UUID resolvedBy,
        String resolutionNote,
        Instant closedAt,
        List<CommentView> comments) {
}
