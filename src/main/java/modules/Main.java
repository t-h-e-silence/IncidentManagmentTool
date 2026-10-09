package modules;

import java.time.Clock;

import modules.controller.IncidentManagementController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Incident management monolith. Users act over HTTP through its single controller,
 * {@link IncidentManagementController}.
 */
@SpringBootApplication
@EnableScheduling
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    /**
     * Time source for all modules; replaced by a fixed clock in tests.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
