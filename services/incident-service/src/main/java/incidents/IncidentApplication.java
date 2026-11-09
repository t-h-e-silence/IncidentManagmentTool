package incidents;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * incident-service: the incident lifecycle and comments. Users act through
 * {@link incidents.controller.IncidentController}; every change is published as an event to RabbitMQ through the
 * outbox, which {@link incidents.service.OutboxRelayJob} relays (ADR D1, D4).
 */
@SpringBootApplication
@EnableScheduling
public class IncidentApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentApplication.class, args);
    }

    /**
     * Time source; replaced by a fixed clock in tests.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
