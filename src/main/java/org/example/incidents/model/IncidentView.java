package org.example.incidents.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.example.common.model.Severity;

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
        List<CommentView> comments) {
}
