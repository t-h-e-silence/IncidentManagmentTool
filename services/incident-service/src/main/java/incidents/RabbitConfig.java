package incidents;

import incidents.model.IncidentEvent;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What incident-service owns in RabbitMQ (ADR D14): the {@value IncidentEvent#EXCHANGE} topic exchange and the
 * {@value #UNROUTED} alternate exchange with its queue, which keeps events no queue is bound for. Declared on the
 * first connection; the consumers declare the exchange too, with exactly these arguments.
 */
@Configuration
public class RabbitConfig {

    public static final String UNROUTED = "unrouted";

    @Bean
    Declarables incidentEventsTopology() {
        FanoutExchange unrouted = new FanoutExchange(UNROUTED, true, false);
        Queue unroutedQueue = QueueBuilder.durable(UNROUTED).quorum().build();
        Binding unroutedBinding = BindingBuilder.bind(unroutedQueue).to(unrouted);
        TopicExchange events = new TopicExchange(IncidentEvent.EXCHANGE, true, false);
        events.addArgument("alternate-exchange", UNROUTED);
        return new Declarables(unrouted, unroutedQueue, unroutedBinding, events);
    }
}
