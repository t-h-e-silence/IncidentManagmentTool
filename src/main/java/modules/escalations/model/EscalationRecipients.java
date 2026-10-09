package modules.escalations.model;

import java.util.List;
import java.util.Optional;

import modules.common.model.Recipient;

/**
 * Who receives which escalation email.
 *
 * @param owningTeam   members of the team that owns the incident after the escalation
 * @param previousTeam members of the team that owned it before; empty if it was not handed over
 * @param reporter     the reporter, if they should be told
 */
public record EscalationRecipients(List<Recipient> owningTeam, List<Recipient> previousTeam,
                                   Optional<Recipient> reporter) {

    public EscalationRecipients {
        owningTeam = List.copyOf(owningTeam);
        previousTeam = List.copyOf(previousTeam);
    }
}
