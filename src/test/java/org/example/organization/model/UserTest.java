package org.example.organization.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.example.common.model.SystemRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserTest {

    private User user(String email) {
        return new User(UUID.randomUUID(), "  Alice  ", email, SystemRole.USER);
    }

    @Test
    void normalizesNameAndEmail() {
        User user = user(" Alice@Example.COM ");

        assertThat(user.getName()).isEqualTo("Alice");
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.canAct()).isTrue();
    }

    @Test
    void rejectsMalformedEmail() {
        for (String email : new String[]{"no-at-sign", "@example.com", "alice@", "a@b@c", " "}) {
            assertThatThrownBy(() -> user(email)).as(email).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void deactivatedUserCannotAct() {
        User user = user("alice@example.com");

        ReflectionTestUtils.setField(user, "active", false);

        assertThat(user.canAct()).isFalse();
    }

    @Test
    void systemUserCannotAct() {
        assertThat(new User(UUID.randomUUID(), "System", "system@ims.invalid", SystemRole.SYSTEM).canAct()).isFalse();
    }
}
