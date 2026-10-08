package org.example.escalations.repository;

import java.util.UUID;

import org.example.escalations.model.Escalation;
import org.springframework.data.repository.Repository;

/**
 * Append only.
 */
public interface EscalationRepository extends Repository<Escalation, UUID> {

    Escalation save(Escalation escalation);
}
