package de.chriscohnen.islandr.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebAuthn binds a credential to a registrable domain. Getting this wrong does
 * not produce an error message — the browser simply does not offer the key, and
 * the admin is left at a login screen that used to work.
 */
class RelyingPartyIdTest {

    @Test
    void aNameBecomesTheRelyingPartyId() {
        assertThat(RelyingPartyId.of("hub.islandr.internal")).isEqualTo("hub.islandr.internal");
        assertThat(RelyingPartyId.of("konsole.firma.de")).isEqualTo("konsole.firma.de");
    }

    @Test
    void thePortIsNotPartOfIt() {
        assertThat(RelyingPartyId.of("hub.islandr.internal:8443")).isEqualTo("hub.islandr.internal");
    }

    @Test
    void theHostIsTreatedCaseInsensitively() {
        assertThat(RelyingPartyId.of("HUB.Islandr.Internal")).isEqualTo("hub.islandr.internal");
    }

    @Test
    void anIpAddressCannotCarryOne() {
        // Not a limitation islandr can lift: there is no registrable domain to
        // bind a credential to. A console reached by IP cannot use keys at all,
        // which is exactly what the hub's own DNS record exists for.
        assertThat(RelyingPartyId.of("10.77.140.1")).isNull();
        assertThat(RelyingPartyId.of("10.77.140.1:8443")).isNull();
        assertThat(RelyingPartyId.of("[fd11:5ee:bad:c0de::1]:8443")).isNull();
        assertThat(RelyingPartyId.isUsable("10.77.140.1")).isFalse();
    }

    @Test
    void localhostIsARealName() {
        // The one host the specification exempts from HTTPS. Development works,
        // which is the point of not special-casing it away.
        assertThat(RelyingPartyId.of("localhost:8080")).isEqualTo("localhost");
        assertThat(RelyingPartyId.isUsable("localhost")).isTrue();
    }

    @Test
    void nothingAtAllIsNotAName() {
        assertThat(RelyingPartyId.of(null)).isNull();
        assertThat(RelyingPartyId.of("  ")).isNull();
    }

    @Test
    void aHostnameThatMerelyLooksNumericIsStillAName() {
        // Four numeric labels are an address; three are not, however odd.
        assertThat(RelyingPartyId.of("10.77.140")).isEqualTo("10.77.140");
        assertThat(RelyingPartyId.of("999.1.1.1")).isEqualTo("999.1.1.1");
    }
}
