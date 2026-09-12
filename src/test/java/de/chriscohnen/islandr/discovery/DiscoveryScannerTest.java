package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the bounded-concurrency scan (ADR-0014, slice 3), fake probe. */
class DiscoveryScannerTest {

    @Test
    void returnsLiveHostsInNumericIpOrderWithTypeGuess() {
        List<String> ips = List.of("10.0.0.2", "10.0.0.10", "10.0.0.3");
        Function<String, HostProbe.ProbeResult> fake = ip -> switch (ip) {
            case "10.0.0.2"  -> new HostProbe.ProbeResult(ip, true, List.of(554), "cam-host", null);  // camera
            case "10.0.0.10" -> new HostProbe.ProbeResult(ip, true, List.of(22), null, null);         // computer
            default          -> new HostProbe.ProbeResult(ip, false, List.of(), null, null);          // dead
        };

        var hosts = new DiscoveryScanner(4).scan(ips, fake);

        // Dead host dropped; .2 sorts before .10 numerically (not lexically).
        assertThat(hosts).extracting(DiscoveryScanner.DiscoveredHost::ip)
                .containsExactly("10.0.0.2", "10.0.0.10");
        assertThat(hosts.get(0).typeGuess()).isEqualTo("camera");
        assertThat(hosts.get(0).hostname()).isEqualTo("cam-host");  // reverse-DNS name passed through
        assertThat(hosts.get(1).typeGuess()).isEqualTo("computer");
    }

    @Test
    void skipsHostsWhoseProbeThrows() {
        Function<String, HostProbe.ProbeResult> fake = ip -> {
            if (ip.equals("10.0.0.1")) throw new RuntimeException("boom");
            return new HostProbe.ProbeResult(ip, true, List.of(80), null, null);
        };

        var hosts = new DiscoveryScanner(2).scan(List.of("10.0.0.1", "10.0.0.2"), fake);

        assertThat(hosts).extracting(DiscoveryScanner.DiscoveredHost::ip).containsExactly("10.0.0.2");
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertThat(new DiscoveryScanner(4).scan(List.of(), ip -> null)).isEmpty();
    }

    @Test
    void reportsProgressOncePerHost_includingDeadOnes() {
        List<String> ips = List.of("10.0.0.1", "10.0.0.2", "10.0.0.3");
        // All dead: progress must still tick once per host, so the UI never freezes.
        Function<String, HostProbe.ProbeResult> fake = ip -> new HostProbe.ProbeResult(ip, false, List.of(), null, null);
        java.util.concurrent.atomic.AtomicInteger progress = new java.util.concurrent.atomic.AtomicInteger();

        new DiscoveryScanner(2).scan(ips, fake, progress::incrementAndGet);

        assertThat(progress.get()).isEqualTo(3);
    }

    @Test
    void handsEachLiveHostToOnFound_asItIsFound() {
        // Issue #75: the callback carries the host itself, so a caller can show
        // rows while the scan runs instead of only a counter.
        List<String> ips = List.of("10.0.0.2", "10.0.0.3", "10.0.0.4");
        Function<String, HostProbe.ProbeResult> fake = ip -> new HostProbe.ProbeResult(
                ip, !ip.equals("10.0.0.3"), List.of(631), "printer-" + ip, null);
        List<DiscoveryScanner.DiscoveredHost> streamed = new CopyOnWriteArrayList<>();

        new DiscoveryScanner(2).scan(ips, fake, () -> {}, streamed::add);

        assertThat(streamed).extracting(DiscoveryScanner.DiscoveredHost::ip)
                .containsExactlyInAnyOrder("10.0.0.2", "10.0.0.4");   // dead .3 never streamed
        assertThat(streamed).allMatch(h -> "printer".equals(h.typeGuess()));
        assertThat(streamed).allMatch(h -> h.hostname() != null);
    }

    @Test
    void streamsAHost_beforeTheScanReturns() {
        // The whole point: a host found early must be visible while slower hosts
        // are still being probed, not held back until every probe has finished.
        CountDownLatch firstStreamed = new CountDownLatch(1);
        CountDownLatch releaseSlowHost = new CountDownLatch(1);
        Function<String, HostProbe.ProbeResult> fake = ip -> {
            if (ip.equals("10.0.0.9")) {
                try {
                    // Hold the scan open until the fast host has already surfaced.
                    if (!releaseSlowHost.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new HostProbe.ProbeResult(ip, true, List.of(80), null, null);
        };
        List<DiscoveryScanner.DiscoveredHost> streamed = new CopyOnWriteArrayList<>();

        Thread scan = new Thread(() -> new DiscoveryScanner(4).scan(
                List.of("10.0.0.9", "10.0.0.1"), fake, () -> {}, h -> {
                    streamed.add(h);
                    firstStreamed.countDown();
                }));
        scan.setDaemon(true);
        scan.start();

        try {
            assertThat(firstStreamed.await(5, TimeUnit.SECONDS))
                    .as("the fast host surfaces while the slow one is still being probed").isTrue();
            assertThat(streamed).extracting(DiscoveryScanner.DiscoveredHost::ip).containsExactly("10.0.0.1");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            releaseSlowHost.countDown();
        }
    }
}
