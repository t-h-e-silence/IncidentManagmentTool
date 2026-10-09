package modules.incidents.model;

import java.time.Instant;
import java.util.UUID;

public record CommentView(UUID id, UUID incidentId, UUID authorId, String text, Instant createdAt) {
}
