package audit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.stereotype.Component;

/**
 * Runs when a message has failed 1 attempt + 3 retries: logs it at ERROR with its event id and correlation id, then
 * rejects it without requeue, so RabbitMQ moves it to the queue's dead-letter queue (ADR D8).
 */
@Component
public class DeadLetterRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);

    private final MessageRecoverer reject = new RejectAndDontRequeueRecoverer();

    @Override
    public void recover(Message message, Throwable cause) {
        MessageProperties properties = message.getMessageProperties();
        log.error("Message {} ({}, correlation id {}) from {} failed after all retries, moving it to the dead-letter "
                        + "queue: {}", properties.getMessageId(), properties.getType(), properties.getCorrelationId(),
                properties.getConsumerQueue(), cause.getCause() != null ? cause.getCause().toString() : cause.toString());
        reject.recover(message, cause);
    }
}
