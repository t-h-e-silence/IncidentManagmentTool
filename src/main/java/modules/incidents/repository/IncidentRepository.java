package modules.incidents.repository;

import java.util.List;
import java.util.UUID;

import modules.incidents.model.Incident;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    List<Incident> findAllByOrderByCreatedAtDesc();

    List<Incident> findByReporterIdOrderByCreatedAtDesc(UUID reporterId);

    List<Incident> findByTeamIdOrderByCreatedAtDesc(UUID teamId);
}
