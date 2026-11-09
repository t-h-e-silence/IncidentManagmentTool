package incidents.service;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events every {@code outbox.relay.interval}, and once an hour deletes events published more than
 * 7 days ago and idempotency keys older than 24 hours (ADR D9, D14).
 */
@Component
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    static final Duration KEEP_PUBLISHED = Duration.ofDays(7);

    private final OutboxService outbox;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public OutboxRelayJob(OutboxService outbox, IdempotencyService idempotency, Clock clock) {
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval}")
    public void relay() {
        try {
            int confirmed = outbox.relayPending();
            if (confirmed > 0) {
                log.debug("Published {} event(s)", confirmed);
            }
        } catch (RuntimeException e) {
            // e.g. database or RabbitMQ unavailable; the next run tries again
            log.error("Outbox relay run failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    public void cleanUp() {
        try {
            int events = outbox.deletePublishedBefore(clock.instant().minus(KEEP_PUBLISHED));
            int keys = idempotency.deleteExpired();
            log.info("Deleted {} published event(s) and {} expired idempotency key(s)", events, keys);
        } catch (RuntimeException e) {
            log.error("Clean-up failed: {}", e.getMessage());
        }
    }
}
