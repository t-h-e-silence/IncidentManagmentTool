package notifications;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What notification-service owns in RabbitMQ (ADR D14): the quorum queues {@value #URGENT_QUEUE} and
 * {@value #NORMAL_QUEUE}, their dead-letter queues behind the shared {@value #DLX} exchange, and their bindings to
 * incident-service's {@value #INCIDENT_EVENTS} and escalation-service's {@value #NOTIFICATION_COMMANDS}. It also
 * declares those two exchanges, with exactly their owners' arguments, so the start order does not matter.
 *
 * <p>Each queue has its own consumer pool ({@code notifications.consumers.*}): more, with a small prefetch, for
 * urgent mail, so it spreads across instances and is never stuck behind normal mail.
 */
@Configuration
public class RabbitConfig {

    public static final String INCIDENT_EVENTS = "incidents.events";
    public static final String NOTIFICATION_COMMANDS = "notifications.commands";
    public static final String URGENT_QUEUE = "notifications.urgent";
    public static final String NORMAL_QUEUE = "notifications.normal";
    public static final String DLX = "dlx";
    public static final String UNROUTED = "unrouted";

    public static final String URGENT_CONTAINER_FACTORY = "urgentContainerFactory";
    public static final String NORMAL_CONTAINER_FACTORY = "normalContainerFactory";

    /** After this many deliveries the message is dead-lettered, e.g. when the service crashes on it each time. */
    static final int DELIVERY_LIMIT = 5;

    @Bean
    Declarables notificationTopology() {
        TopicExchange incidentEvents = new TopicExchange(INCIDENT_EVENTS, true, false);
        incidentEvents.addArgument("alternate-exchange", UNROUTED);
        DirectExchange commands = new DirectExchange(NOTIFICATION_COMMANDS, true, false);
        commands.addArgument("alternate-exchange", UNROUTED);
        DirectExchange dlx = new DirectExchange(DLX, true, false);
        Queue urgent = queue(URGENT_QUEUE);
        Queue normal = queue(NORMAL_QUEUE);
        Queue urgentDlq = QueueBuilder.durable(URGENT_QUEUE + ".dlq").quorum().build();
        Queue normalDlq = QueueBuilder.durable(NORMAL_QUEUE + ".dlq").quorum().build();
        return new Declarables(incidentEvents, commands, dlx, urgent, normal, urgentDlq, normalDlq,
                bind(urgent, incidentEvents, "incident.created.urgent"),
                bind(urgent, incidentEvents, "incident.status-changed.urgent"),
                bind(urgent, commands, "notification.email-requested.urgent"),
                bind(normal, incidentEvents, "incident.created.normal"),
                bind(normal, incidentEvents, "incident.status-changed.normal"),
                bind(normal, commands, "notification.email-requested.normal"),
                BindingBuilder.bind(urgentDlq).to(dlx).with(URGENT_QUEUE),
                BindingBuilder.bind(normalDlq).to(dlx).with(NORMAL_QUEUE));
    }

    private static Queue queue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(name)
                // At-least-once dead-lettering needs reject-publish; by default quorum queues dead-letter at most once.
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .deliveryLimit(DELIVERY_LIMIT)
                .build();
    }

    private static Binding bind(Queue queue, Exchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey).noargs();
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

    @Bean(URGENT_CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory urgentContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            @Value("${notifications.consumers.urgent.concurrency}") int concurrency,
            @Value("${notifications.consumers.urgent.prefetch}") int prefetch) {
        return containerFactory(configurer, connectionFactory, concurrency, prefetch);
    }

    @Bean(NORMAL_CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory normalContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            @Value("${notifications.consumers.normal.concurrency}") int concurrency,
            @Value("${notifications.consumers.normal.prefetch}") int prefetch) {
        return containerFactory(configurer, connectionFactory, concurrency, prefetch);
    }

    /**
     * Spring Boot's listener settings (acknowledgement, retry, the {@code MessageRecoverer}), with this pool's size.
     */
    private static SimpleRabbitListenerContainerFactory containerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            int concurrency, int prefetch) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        factory.setPrefetchCount(prefetch);
        return factory;
    }
}
