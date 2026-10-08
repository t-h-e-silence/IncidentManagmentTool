package org.example.incidents.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.example.incidents.model.Incident;
import org.example.incidents.model.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    List<Incident> findAllByOrderByCreatedAtDesc();

    List<Incident> findByReporterIdOrderByCreatedAtDesc(UUID reporterId);

    List<Incident> findByTeamIdOrderByCreatedAtDesc(UUID teamId);

    /**
     * Severity is stored as {@code SEV1..SEV4}, so ascending order puts the most severe first.
     */
    List<Incident> findByTeamIdAndStatusInOrderBySeverityAscCreatedAtAsc(UUID teamId,
                                                                         Collection<IncidentStatus> statuses);
}
