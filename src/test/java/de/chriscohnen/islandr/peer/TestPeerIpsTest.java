package de.chriscohnen.islandr.peer;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the two properties {@link TestPeerIps} exists for — a plain unit test,
 *  deliberately not a {@code @QuarkusTest}: the allocator touches nothing. */
class TestPeerIpsTest {

    @Test
    void handsOutEachAddressOnlyOnce() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(TestPeerIps.next()))
                    .as("address %d was already handed out", i)
                    .isTrue();
        }
    }

    /** The suite's literal addresses live in 10.8.0.x and 10.9.0.x through
     *  10.9.3.x; staying clear of those is what keeps the allocator from
     *  landing on one. */
    @Test
    void neverUsesABlockTheSuitesLiteralAddressesLiveIn() {
        for (int i = 0; i < 1000; i++) {
            assertThat(TestPeerIps.next())
                    .doesNotStartWith("10.8.")
                    .doesNotStartWith("10.9.0.")
                    .doesNotStartWith("10.9.1.")
                    .doesNotStartWith("10.9.2.")
                    .doesNotStartWith("10.9.3.");
        }
    }
}
