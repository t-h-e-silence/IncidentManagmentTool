package modules.escalations.repository;

import java.util.UUID;

import modules.escalations.model.Escalation;
import org.springframework.data.repository.Repository;

/**
 * Append only.
 */
public interface EscalationRepository extends Repository<Escalation, UUID> {

    Escalation save(Escalation escalation);
}
