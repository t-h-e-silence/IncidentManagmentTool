package escalations;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * escalation-service: records escalations and de-escalations from incident events
 * ({@link escalations.service.IncidentEventListener}) and requests their emails from notification-service through
 * its outbox ({@link escalations.service.OutboxRelayJob}). No HTTP API (ADR D1, D2).
 */
@SpringBootApplication
@EnableScheduling
public class EscalationApplication {

    public static void main(String[] args) {
        SpringApplication.run(EscalationApplication.class, args);
    }

    /**
     * Time source; replaced by a fixed clock in tests.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
