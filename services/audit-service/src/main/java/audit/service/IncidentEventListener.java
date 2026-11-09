package audit.service;

import audit.RabbitConfig;
import audit.model.IncidentEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@value RabbitConfig#QUEUE}. The message is acknowledged after {@link AuditService#recordIncidentEvent}
 * commits; a failure is retried 3 times (1 s, 5 s, 25 s) and then dead-lettered to {@value RabbitConfig#DLQ}
 * (ADR D8).
 */
@Component
public class IncidentEventListener {

    private final AuditService audit;

    public IncidentEventListener(AuditService audit) {
        this.audit = audit;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onIncidentEvent(IncidentEvent event) {
        audit.recordIncidentEvent(event);
    }
}
