package escalations.service;

import escalations.RabbitConfig;
import escalations.model.IncidentEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@value RabbitConfig#QUEUE}. The message is acknowledged after {@link EscalationService#record} commits;
 * a failure is retried 3 times (1 s, 5 s, 25 s) and then dead-lettered to {@value RabbitConfig#DLQ} (ADR D8).
 */
@Component
public class IncidentEventListener {

    private final EscalationService escalations;

    public IncidentEventListener(EscalationService escalations) {
        this.escalations = escalations;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onIncidentEvent(IncidentEvent event) {
        escalations.record(event);
    }
}
