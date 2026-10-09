package modules.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Someone who receives an email: an active user with a contact address.
 */
public record Recipient(UUID userId, String name, String email) {

    public Recipient {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(email, "email");
    }
}
