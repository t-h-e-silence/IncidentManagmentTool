package incidents.service;

import java.time.Instant;

import incidents.model.IncidentEvent;

/**
 * Transactional outbox (ADR D4): events are stored with the incident change and published to RabbitMQ afterwards.
 */
public interface OutboxService {

    /**
     * Stores the event in the caller's transaction; it is published only if that transaction commits.
     */
    void publish(IncidentEvent event);

    /**
     * Publishes the oldest unpublished events (at most {@code outbox.relay.batch-size}) and marks those RabbitMQ
     * confirmed; the others are retried on the next run. Called by {@link OutboxRelayJob}.
     *
     * @return number of events confirmed
     */
    int relayPending();

    /**
     * @return number of published events deleted
     */
    int deletePublishedBefore(Instant before);
}
