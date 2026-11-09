package escalations.repository;

import java.util.UUID;

import escalations.model.Escalation;
import org.springframework.data.repository.Repository;

/**
 * Append only.
 */
public interface EscalationRepository extends Repository<Escalation, UUID> {

    Escalation save(Escalation escalation);
}
