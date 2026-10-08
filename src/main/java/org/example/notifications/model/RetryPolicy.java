package org.example.notifications.model;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Delays between delivery attempts. Each entry is one retry, so an email is attempted at most
 * {@code backoff.size() + 1} times before it is dead-lettered.
 */
public record RetryPolicy(List<Duration> backoff) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)));

    public RetryPolicy {
        backoff = List.copyOf(backoff);
        if (backoff.stream().anyMatch(delay -> delay.isNegative() || delay.isZero())) {
            throw new IllegalArgumentException("backoff delays must be positive");
        }
    }

    /**
     * @param attemptsMade attempts so far, including the one that just failed
     * @return delay before the next attempt, or empty when the email must be dead-lettered
     */
    public Optional<Duration> delayAfterFailedAttempt(int attemptsMade) {
        if (attemptsMade < 1) {
            throw new IllegalArgumentException("attemptsMade must be >= 1");
        }
        return attemptsMade <= backoff.size() ? Optional.of(backoff.get(attemptsMade - 1)) : Optional.empty();
    }
}
