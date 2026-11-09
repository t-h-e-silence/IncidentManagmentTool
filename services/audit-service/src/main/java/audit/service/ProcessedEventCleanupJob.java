package audit.service;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once an hour deletes processed-event marks older than 30 days, far longer than any redelivery window (ADR D14).
 */
@Component
public class ProcessedEventCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventCleanupJob.class);

    static final Duration KEEP = Duration.ofDays(30);

    private final AuditService audit;
    private final Clock clock;

    public ProcessedEventCleanupJob(AuditService audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void run() {
        try {
            int deleted = audit.deleteProcessedBefore(clock.instant().minus(KEEP));
            log.info("Deleted {} processed-event mark(s)", deleted);
        } catch (RuntimeException e) {
            log.error("Clean-up failed: {}", e.getMessage());
        }
    }
}
