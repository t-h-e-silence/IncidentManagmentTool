package modules.organization.repository;

import java.util.List;
import java.util.UUID;

import modules.organization.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    List<Team> findAllByOrderByName();

    /**
     * Ids of non-archived teams the user is a member of.
     */
    @Query("select t.id from Team t join t.members m where key(m) = :userId and t.archivedAt is null")
    List<UUID> findActiveTeamIdsOfUser(@Param("userId") UUID userId);
}
