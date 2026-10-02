package org.example.notifications.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void defaultRetriesAfterOneFiveAndFifteenMinutesThenStops() {
        RetryPolicy policy = RetryPolicy.DEFAULT;

        assertThat(policy.maxAttempts()).isEqualTo(4);
        assertThat(policy.delayAfterFailedAttempt(1)).contains(Duration.ofMinutes(1));
        assertThat(policy.delayAfterFailedAttempt(3)).contains(Duration.ofMinutes(15));
        assertThat(policy.delayAfterFailedAttempt(4)).isEmpty();
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> new RetryPolicy(List.of(Duration.ZERO))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.DEFAULT.delayAfterFailedAttempt(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
