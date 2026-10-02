package org.example.organization.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.example.common.exception.BusinessRuleException;
import org.example.common.model.TeamRole;
import org.junit.jupiter.api.Test;

class TeamTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final Team team = new Team(UUID.randomUUID(), "Database", alice, TeamRole.TEAM_LEAD);

    @Test
    void startsWithItsFirstMember() {
        assertThat(team.roleOf(alice)).contains(TeamRole.TEAM_LEAD);
        assertThat(team.roleOf(bob)).isEmpty();
    }

    @Test
    void addsAndRemovesMembers() {
        team.addMember(bob, TeamRole.RESPONDER);
        team.removeMember(alice);

        assertThat(team.getMembers()).containsOnlyKeys(bob);
    }

    @Test
    void rejectsDuplicateMemberAndKeepsAtLeastOne() {
        assertThatThrownBy(() -> team.addMember(alice, TeamRole.RESPONDER)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> team.removeMember(alice)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void archivedTeamCannotChange() {
        team.archive(Instant.now());

        assertThat(team.isArchived()).isTrue();
        assertThatThrownBy(() -> team.addMember(bob, TeamRole.RESPONDER)).isInstanceOf(BusinessRuleException.class);
    }
}
