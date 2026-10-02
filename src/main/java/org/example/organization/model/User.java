package org.example.organization.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.example.common.exception.BusinessRuleException;
import org.example.common.model.SystemRole;
import org.example.common.model.Text;

/**
 * A person using the system. Other modules keep the user id (reporter, comment author, notification recipient,
 * audit actor), so a user is deactivated, never deleted.
 */
@Entity
@Table(name = "app_user", schema = "organization")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Stored lower-case; unique. */
    @Column(nullable = false, length = 320, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_role", nullable = false, length = 20)
    private SystemRole systemRole;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Version
    private long version;

    protected User() {
        // for JPA
    }

    public User(UUID id, String name, String email, SystemRole systemRole) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Text.require(name, "name", 200);
        this.email = normalizeEmail(email);
        this.systemRole = Objects.requireNonNull(systemRole, "systemRole");
        this.active = true;
    }

    /**
     * The user can no longer act or be notified.
     */
    public void deactivate(Instant now) {
        if (!active) {
            throw new BusinessRuleException("User " + id + " is already deactivated");
        }
        active = false;
        deactivatedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Only an active, non-system user may call the controller and be emailed.
     */
    public boolean canAct() {
        return active && systemRole != SystemRole.SYSTEM;
    }

    public UserView toView() {
        return new UserView(id, name, email, systemRole, active);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public SystemRole getSystemRole() {
        return systemRole;
    }

    public boolean isActive() {
        return active;
    }

    private static String normalizeEmail(String email) {
        String value = Text.require(email, "email", 320).toLowerCase(Locale.ROOT);
        int at = value.indexOf('@');
        if (at <= 0 || at == value.length() - 1 || value.indexOf('@', at + 1) >= 0) {
            throw new IllegalArgumentException("email must look like name@domain");
        }
        return value;
    }
}
