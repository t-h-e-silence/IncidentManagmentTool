package audit;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What audit-service owns in RabbitMQ (ADR D14): its quorum queue {@value #QUEUE}, bound to every incident event,
 * and its dead-letter queue {@value #DLQ} behind the shared {@value #DLX} exchange. It also declares the
 * {@value #INCIDENT_EVENTS} exchange it binds to, with exactly the arguments incident-service uses, so the start
 * order does not matter.
 */
@Configuration
public class RabbitConfig {

    public static final String INCIDENT_EVENTS = "incidents.events";
    public static final String QUEUE = "audit.incident-events";
    public static final String DLQ = QUEUE + ".dlq";
    public static final String DLX = "dlx";

    /** After this many deliveries the message is dead-lettered, e.g. when the service crashes on it each time. */
    static final int DELIVERY_LIMIT = 5;

    @Bean
    Declarables auditTopology() {
        TopicExchange incidentEvents = new TopicExchange(INCIDENT_EVENTS, true, false);
        incidentEvents.addArgument("alternate-exchange", "unrouted");
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
        Binding events = BindingBuilder.bind(queue).to(incidentEvents).with("incident.#");
        Binding deadLetters = BindingBuilder.bind(dlq).to(dlx).with(QUEUE);
        return new Declarables(incidentEvents, dlx, queue, dlq, events, deadLetters);
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
