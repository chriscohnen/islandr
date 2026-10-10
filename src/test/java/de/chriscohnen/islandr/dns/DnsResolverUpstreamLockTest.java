package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.settings.Settings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dns-upstream-lock-pinning: {@code queryUpstream} used to guard its
 * send-then-receive pair with {@code synchronized (sock)}. A virtual thread
 * blocked inside a {@code synchronized} block stays pinned to its carrier OS
 * thread for the duration (JEP 444, still true on JDK 21) — on a single-vCPU
 * hub, the one carrier available would be held hostage by a slow or lost
 * upstream reply (up to {@code UPSTREAM_TIMEOUT_MS}), stalling every other
 * in-flight query, including ones answered entirely from the local zone and
 * never touching an upstream at all. Replaced with a {@link
 * java.util.concurrent.locks.ReentrantLock} per upstream, which parks the
 * waiting/blocked virtual thread without pinning its carrier.
 *
 * <p>This test demonstrates the invariant the fix exists for — a local
 * answer completes promptly while a hung upstream query is in flight on a
 * separate virtual thread — using the real {@code queryUpstreamForPreview}/
 * {@code resolve} entry points. It does not reproduce carrier starvation
 * itself: that needs the virtual-thread scheduler constrained to a single
 * carrier ({@code -Djdk.virtualThreadScheduler.parallelism=1}), a JVM-startup
 * property that can't be scoped to one test in this project's shared test
 * JVM without forking a separate process for it, and forcing it suite-wide
 * would slow and risk destabilizing every other test that relies on virtual
 * threads. On typical multi-core dev/CI hardware this passes both before and
 * after the fix; its value is as a tripwire for a future regression in the
 * locking itself (e.g. an accidental revert to a shared/global lock), not as
 * proof the pinning hazard is gone.
 */
@QuarkusTest
class DnsResolverUpstreamLockTest {

    // RFC 5737 TEST-NET-3 — reserved, never routed, nothing answers there.
    // Same address DnsResolverServiceTest already uses for "never responds".
    private static final String NON_RESPONDING_UPSTREAM = "203.0.113.9";

    @Inject DnsResolverService resolver;
    @Inject DnsQueryHandler queryHandler;

    private String originalUpstream;
    private boolean originalEnabled;

    @AfterEach
    void restoreUpstream() {
        if (originalUpstream != null) setUpstream(originalUpstream);
        setEnabled(originalEnabled);
    }

    @Test
    void aLocalAnswerCompletesPromptly_whileAnUpstreamQueryIsHungOnAnotherVirtualThread() {
        originalUpstream = currentUpstream();
        originalEnabled = currentEnabled();
        setUpstream(NON_RESPONDING_UPSTREAM);
        setEnabled(true);

        CompletableFuture<DnsResolverService.UpstreamAnswer> hungQuery =
                CompletableFuture.supplyAsync(() -> resolver.queryUpstreamForPreview("example.invalid"),
                        Thread.ofVirtual()::start);

        // Give the hung query a moment to actually be inside receive() —
        // send() on UDP returns immediately, so this is enough without
        // needing the full UPSTREAM_TIMEOUT_MS.
        sleepQuietly(100);

        String hubName = "hub." + queryHandler.currentConfig().zone();
        Instant before = Instant.now();
        DnsQueryHandler.Resolution hubAnswer = queryHandler.resolve(hubName, "10.8.0.2");
        Duration elapsed = Duration.between(before, Instant.now());

        assertThat(hubAnswer).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        assertThat(elapsed)
                .as("a local-zone answer must not wait on an unrelated upstream's receive()")
                .isLessThan(Duration.ofMillis(500));

        hungQuery.join(); // let it finish (times out on its own) before cleanup
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Transactional
    void setUpstream(String upstream) {
        Settings.<Settings>findById(Settings.SINGLETON_ID).dnsResolverUpstream = upstream;
    }

    @Transactional
    String currentUpstream() {
        return Settings.<Settings>findById(Settings.SINGLETON_ID).dnsResolverUpstream;
    }

    @Transactional
    void setEnabled(boolean enabled) {
        Settings.<Settings>findById(Settings.SINGLETON_ID).dnsResolverEnabled = enabled;
    }

    @Transactional
    boolean currentEnabled() {
        return Settings.<Settings>findById(Settings.SINGLETON_ID).dnsResolverEnabled;
    }
}
