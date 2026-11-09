package notifications;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * notification-service: all emails of the incident management system. Consumes incident events and escalation
 * email commands ({@link notifications.service.NotificationListener}) and sends the emails over SMTP
 * ({@link notifications.service.EmailDeliveryJob}). No HTTP API, so any number of instances can run (ADR D1, D8).
 */
@SpringBootApplication
@EnableScheduling
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }

    /**
     * Time source; replaced by a fixed clock in tests.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
