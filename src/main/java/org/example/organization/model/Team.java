package org.example.organization.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.example.common.exception.BusinessRuleException;
import org.example.common.model.TeamRole;
import org.example.common.model.Text;

/**
 * A responder team with its members. A team always has at least one member, and is archived, never deleted,
 * because incidents keep its id.
 */
@Entity
@Table(name = "team", schema = "organization")
public class Team {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200, unique = true)
    private String name;

    /** user id → role, stored in {@code team_membership}. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "team_membership", schema = "organization", joinColumns = @JoinColumn(name = "team_id"))
    @MapKeyColumn(name = "user_id")
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Map<UUID, TeamRole> members = new LinkedHashMap<>();

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    private long version;

    protected Team() {
        // for JPA
    }

    public Team(UUID id, String name, UUID firstMember, TeamRole firstMemberRole) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Text.require(name, "name", 200);
        addMember(firstMember, firstMemberRole);
    }

    public void addMember(UUID userId, TeamRole role) {
        requireNotArchived();
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        if (members.containsKey(userId)) {
            throw new BusinessRuleException("User " + userId + " is already a member of team " + name);
        }
        members.put(userId, role);
    }

    public void removeMember(UUID userId) {
        requireNotArchived();
        if (!members.containsKey(userId)) {
            throw new BusinessRuleException("User " + userId + " is not a member of team " + name);
        }
        if (members.size() == 1) {
            throw new BusinessRuleException("Cannot remove the last member of team " + name);
        }
        members.remove(userId);
    }

    public void archive(Instant now) {
        requireNotArchived();
        archivedAt = Objects.requireNonNull(now, "now");
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public Optional<TeamRole> roleOf(UUID userId) {
        return Optional.ofNullable(members.get(userId));
    }

    /**
     * All members including deactivated users; filtering is the service's job.
     */
    public Map<UUID, TeamRole> getMembers() {
        return Map.copyOf(members);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    private void requireNotArchived() {
        if (archivedAt != null) {
            throw new BusinessRuleException("Team " + name + " is archived");
        }
    }
}
