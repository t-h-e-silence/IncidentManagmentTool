package escalations;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What escalation-service owns in RabbitMQ (ADR D14):
 * <ul>
 *   <li>as a publisher: the {@value #NOTIFICATION_COMMANDS} direct exchange and the {@value #UNROUTED} alternate
 *       exchange with its queue;</li>
 *   <li>as a consumer: its quorum queue {@value #QUEUE}, bound to escalations and de-escalations of
 *       {@value #INCIDENT_EVENTS}, and its dead-letter queue {@value #DLQ} behind the shared {@value #DLX}
 *       exchange.</li>
 * </ul>
 * Exchanges owned by others are declared with exactly their owners' arguments, so the start order does not matter.
 */
@Configuration
public class RabbitConfig {

    public static final String INCIDENT_EVENTS = "incidents.events";
    public static final String NOTIFICATION_COMMANDS = "notifications.commands";
    public static final String QUEUE = "escalations.incident-events";
    public static final String DLQ = QUEUE + ".dlq";
    public static final String DLX = "dlx";
    public static final String UNROUTED = "unrouted";

    /** After this many deliveries the message is dead-lettered, e.g. when the service crashes on it each time. */
    static final int DELIVERY_LIMIT = 5;

    @Bean
    Declarables escalationTopology() {
        FanoutExchange unrouted = new FanoutExchange(UNROUTED, true, false);
        Queue unroutedQueue = QueueBuilder.durable(UNROUTED).quorum().build();
        DirectExchange commands = new DirectExchange(NOTIFICATION_COMMANDS, true, false);
        commands.addArgument("alternate-exchange", UNROUTED);
        TopicExchange incidentEvents = new TopicExchange(INCIDENT_EVENTS, true, false);
        incidentEvents.addArgument("alternate-exchange", UNROUTED);
        DirectExchange dlx = new DirectExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE)
                .quorum()
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(QUEUE)
                // At-least-once dead-lettering needs reject-publish; by default quorum queues dead-letter at most once.
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .deliveryLimit(DELIVERY_LIMIT)
                .build();
        Queue dlq = QueueBuilder.durable(DLQ).quorum().build();
        return new Declarables(unrouted, unroutedQueue, BindingBuilder.bind(unroutedQueue).to(unrouted), commands,
                incidentEvents, dlx, queue, dlq,
                bind(queue, incidentEvents, "incident.escalated.*"),
                bind(queue, incidentEvents, "incident.de-escalated.*"),
                BindingBuilder.bind(dlq).to(dlx).with(QUEUE));
    }

    private static Binding bind(Queue queue, TopicExchange exchange, String pattern) {
        return BindingBuilder.bind(queue).to(exchange).with(pattern);
    }

    /**
     * JSON bodies are mapped to this service's own classes (the listener's parameter type), never to a
     * {@code __TypeId__} header (ADR D14).
     */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper json) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(json);
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }
}
