package organization.model;

import java.util.Objects;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Someone who receives an email: an active user with a contact address.
 */
@Schema(description = "Someone who may be emailed: an active user with a contact address")
public record Recipient(
        @Schema(description = "User id", example = "20000000-0000-0000-0000-000000000003") UUID userId,
        @Schema(description = "Display name, used in the greeting", example = "Bob Reporter") String name,
        @Schema(description = "Email address", example = "bob@example.com") String email) {

    public Recipient {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(email, "email");
    }
}
