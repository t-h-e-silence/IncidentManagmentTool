package org.example.organization.model;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.example.common.model.Text;

/**
 * What a reporter chooses ("Payments", "VPN") and the team that handles it. Deactivated, never deleted.
 */
@Entity
@Table(name = "category", schema = "organization")
public class Category {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(nullable = false)
    private boolean active;

    @Version
    private long version;

    protected Category() {
        // for JPA
    }

    public Category(UUID id, String name, UUID teamId) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Text.require(name, "name", 100);
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.active = true;
    }

    public void deactivate() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    public CategoryView toView() {
        return new CategoryView(id, name);
    }

    public CategoryRouting toRouting() {
        return new CategoryRouting(id, name, teamId);
    }
}
