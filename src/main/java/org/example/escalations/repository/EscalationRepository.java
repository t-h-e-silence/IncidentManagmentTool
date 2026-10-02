package org.example.escalations.repository;

import java.util.List;
import java.util.UUID;

import org.example.escalations.model.Escalation;
import org.springframework.data.repository.Repository;

/**
 * Append and read only.
 */
public interface EscalationRepository extends Repository<Escalation, UUID> {

    Escalation save(Escalation escalation);

    List<Escalation> findByIncidentIdOrderByEscalatedAtAsc(UUID incidentId);
}
