package escalations.service;

import java.time.Instant;

import escalations.model.EmailRequested;

/**
 * Transactional outbox (ADR D4): email commands are stored with the escalation record and published to RabbitMQ
 * afterwards.
 */
public interface OutboxService {

    /**
     * Stores the command in the caller's transaction; it is published only if that transaction commits.
     */
    void publish(EmailRequested command);

    /**
     * Publishes the oldest unpublished commands (at most {@code outbox.relay.batch-size}) and marks those RabbitMQ
     * confirmed; the others are retried on the next run. Called by {@link OutboxRelayJob}.
     *
     * @return number of commands confirmed
     */
    int relayPending();

    /**
     * @return number of published commands deleted
     */
    int deletePublishedBefore(Instant before);
}
