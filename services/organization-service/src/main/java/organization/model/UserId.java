package organization.model;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The id of a user, as answered by {@code GET /internal/users/{user}/id}.
 */
@Schema(description = "The id of a user")
public record UserId(
        @Schema(description = "User id; an id given in the path is returned as it is", example = "20000000-0000-0000-0000-000000000003") UUID id) {
}
