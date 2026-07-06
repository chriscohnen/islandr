package de.chriscohnen.islandr.proxy;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdapterMode#resolve} (design §8, D3): the single rule
 * both adapter producers share. Explicit config wins, else a container defaults
 * to socket, else mock. "In a container" is only a default, never an override.
 */
class AdapterModeTest {

    @Test
    void explicitModeAlwaysWins_evenInContainer() {
        assertThat(AdapterMode.resolve(Optional.of("real"), true)).isEqualTo("real");
        assertThat(AdapterMode.resolve(Optional.of("mock"), true)).isEqualTo("mock");
    }

    @Test
    void unsetInContainer_defaultsToSocket() {
        assertThat(AdapterMode.resolve(Optional.empty(), true)).isEqualTo("socket");
    }

    /** Safety net: on a bare host (no Docker) an unset mode never resolves to socket. */
    @Test
    void unsetOnBareHost_defaultsToMock_neverSocket() {
        String resolved = AdapterMode.resolve(Optional.empty(), false);
        assertThat(resolved).isEqualTo("mock");
        assertThat(resolved).isNotEqualTo("socket");
    }

    /** On a bare host, an explicit real deployment stays real — the proxy path is not forced. */
    @Test
    void explicitRealOnBareHost_staysReal() {
        assertThat(AdapterMode.resolve(Optional.of("real"), false)).isEqualTo("real");
    }

    @Test
    void blankModeIsTreatedAsUnset() {
        assertThat(AdapterMode.resolve(Optional.of("  "), true)).isEqualTo("socket");
    }
}
