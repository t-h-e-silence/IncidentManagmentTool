package modules.organization.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import modules.common.exception.BusinessRuleException;
import modules.common.model.TeamRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TeamTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final Team team = new Team(UUID.randomUUID(), "Database", alice, TeamRole.TEAM_LEAD);

    @Test
    void startsWithItsFirstMemberAndAddsOthers() {
        team.addMember(bob, TeamRole.RESPONDER);

        assertThat(team.getMembers())
                .containsEntry(alice, TeamRole.TEAM_LEAD)
                .containsEntry(bob, TeamRole.RESPONDER);
    }

    @Test
    void rejectsDuplicateMember() {
        assertThatThrownBy(() -> team.addMember(alice, TeamRole.RESPONDER)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void archivedTeamCannotChange() {
        ReflectionTestUtils.setField(team, "archivedAt", Instant.now());

        assertThat(team.isArchived()).isTrue();
        assertThatThrownBy(() -> team.addMember(bob, TeamRole.RESPONDER)).isInstanceOf(BusinessRuleException.class);
    }
}
