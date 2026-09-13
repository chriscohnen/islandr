package de.chriscohnen.islandr.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #80: a failed login cost the attacker one PBKDF2 verification — the
 * hub's cost, not theirs — and nothing slowed the next guess down.
 *
 * <p>Plain JUnit: the backoff is arithmetic over two counters, and pinning it
 * here means the integration tests do not have to wait on real delays.
 */
class LoginThrottleTest {

    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        throttle = new LoginThrottle();
        throttle.freeAttempts = 2;
        throttle.baseDelayMs = 100;
        throttle.maxDelayMs = 5000;
        throttle.windowMinutes = 15;
        throttle.maxConcurrent = 2;
    }

    @Test
    void theFirstFailuresAreAnsweredAtFullSpeed() {
        assertThat(throttle.delayMillis("a@firma.de", "1.2.3.4")).isZero();
        throttle.recordFailure("a@firma.de", "1.2.3.4");
        assertThat(throttle.delayMillis("a@firma.de", "1.2.3.4")).isZero();
        throttle.recordFailure("a@firma.de", "1.2.3.4");
        assertThat(throttle.delayMillis("a@firma.de", "1.2.3.4")).isZero();
    }

    @Test
    void eachFurtherAttemptIsAnsweredMoreSlowlyThanTheLast() {
        long previous = -1;
        for (int i = 0; i < 6; i++) {
            throttle.recordFailure("a@firma.de", "1.2.3.4");
            long delay = throttle.delayMillis("a@firma.de", "1.2.3.4");
            if (i >= 2) {
                assertThat(delay).as("attempt %d", i).isGreaterThan(previous);
            }
            previous = delay;
        }
        assertThat(previous).isLessThanOrEqualTo(throttle.maxDelayMs);
    }

    @Test
    void theDelayIsCapped() {
        for (int i = 0; i < 60; i++) throttle.recordFailure("a@firma.de", "1.2.3.4");
        assertThat(throttle.delayMillis("a@firma.de", "1.2.3.4")).isEqualTo(throttle.maxDelayMs);
    }

    /**
     * The delay must not become the user-enumeration oracle the dummy PBKDF2
     * run exists to prevent: it is keyed on the submitted username, and the
     * throttle never learns whether that account exists.
     */
    @Test
    void anUnknownUsernameIsSlowedDownExactlyLikeAKnownOne() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("real@firma.de", "1.2.3.4");
            throttle.recordFailure("nobody@firma.de", "5.6.7.8");
        }
        assertThat(throttle.delayMillis("nobody@firma.de", "5.6.7.8"))
                .isEqualTo(throttle.delayMillis("real@firma.de", "1.2.3.4"));
    }

    /**
     * Password spraying tries each account once or twice, so a per-account
     * counter alone would never fire. The per-address counter is what catches
     * it — and the slower of the two wins.
     */
    @Test
    void sprayingManyAccountsFromOneAddressIsStillSlowedDown() {
        for (int i = 0; i < 5; i++) throttle.recordFailure("user" + i + "@firma.de", "9.9.9.9");
        assertThat(throttle.delayMillis("never-tried@firma.de", "9.9.9.9")).isPositive();
        // A different address is unaffected — one attacker must not slow
        // everyone else down.
        assertThat(throttle.delayMillis("never-tried@firma.de", "10.0.0.1")).isZero();
    }

    @Test
    void aCorrectPasswordClearsTheCounters() {
        for (int i = 0; i < 5; i++) throttle.recordFailure("a@firma.de", "1.2.3.4");
        assertThat(throttle.delayMillis("a@firma.de", "1.2.3.4")).isPositive();
        throttle.recordSuccess("a@firma.de", "1.2.3.4");
        assertThat(throttle.delayMillis("a@firma.de", "1.2.3.4")).isZero();
    }

    @Test
    void counterExpiresAfterTheQuietWindow() {
        for (int i = 0; i < 5; i++) throttle.recordFailure("a@firma.de", "1.2.3.4");
        assertThat(throttle.delayMillis("a@firma.de", "1.2.3.4")).isPositive();
        throttle.windowMinutes = 0;   // everything is now older than the window
        sleepBriefly();
        assertThat(throttle.delayMillis("a@firma.de", "1.2.3.4")).isZero();
    }

    /** With the window at zero, "older than the window" needs the clock to move. */
    private static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The ceiling is what stops the delay being sidestepped in parallel. */
    @Test
    void concurrentAttemptsAreCapped() {
        assertThat(throttle.tryEnter()).isTrue();
        assertThat(throttle.tryEnter()).isTrue();
        assertThat(throttle.tryEnter()).as("third attempt exceeds max-concurrent=2").isFalse();
        throttle.exit();
        assertThat(throttle.tryEnter()).isTrue();
    }
}
