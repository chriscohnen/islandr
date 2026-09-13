package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #80: a ban is only as good as the address it names, and there are two
 * ways to get that wrong. Ban the socket peer behind Cloudflare and you ban
 * Cloudflare — everyone, including yourself — while the attacker keeps going.
 * Trust a forwarded header unconditionally and the ban becomes a weapon: the
 * attacker rotates the header to evade it, and can name a third party instead.
 *
 * <p>So the header counts only when the request actually came from a proxy the
 * operator named. These tests pin both halves of that.
 */
class ClientAddressTest {

    /**
     * The values live in Settings since they are operational configuration an
     * admin fixes at runtime, so the seam here is a stubbed SettingsService
     * rather than injected config properties.
     */
    private static ClientAddress resolver(String trustedProxies, String header) {
        Settings stub = new Settings();
        stub.trustedProxies = trustedProxies;
        stub.clientIpHeader = header;
        ClientAddress c = new ClientAddress();
        c.settings = new SettingsService() {
            @Override
            public Settings get() {
                return stub;
            }
        };
        return c;
    }

    @Test
    void withNoProxyConfigured_theSocketPeerIsUsed() {
        ClientAddress c = resolver("", "X-Forwarded-For");
        assertThat(c.resolve("203.0.113.9:41234", null)).isEqualTo("203.0.113.9");
    }

    /** The header on its own say-so is worth nothing. */
    @Test
    void anUntrustedSenderCannotSpeakForSomeoneElse() {
        ClientAddress c = resolver("", "X-Forwarded-For");
        assertThat(c.resolve("203.0.113.9", "1.2.3.4")).isEqualTo("203.0.113.9");
    }

    @Test
    void aTrustedProxyIsBelieved_andIsNeverItselfTheAddressReturned() {
        ClientAddress c = resolver("10.0.0.0/24", "X-Forwarded-For");
        assertThat(c.resolve("10.0.0.5", "198.51.100.7")).isEqualTo("198.51.100.7");
    }

    /** Everything left of the last untrusted hop was written by nobody we know. */
    @Test
    void aForgedPrefixInTheChainIsIgnored() {
        ClientAddress c = resolver("10.0.0.0/24", "X-Forwarded-For");
        assertThat(c.resolve("10.0.0.5", "1.1.1.1, 198.51.100.7")).isEqualTo("198.51.100.7");
    }

    /** A chain of trusted proxies resolves past all of them to the client. */
    @Test
    void multipleTrustedHopsResolveToTheClient() {
        ClientAddress c = resolver("10.0.0.0/24, 172.16.0.0/12", "X-Forwarded-For");
        assertThat(c.resolve("10.0.0.5", "198.51.100.7, 172.16.4.2")).isEqualTo("198.51.100.7");
    }

    @Test
    void theHeaderNameIsConfigurable_forCloudflare() {
        // of() reads whichever header the configuration names; resolve() is
        // handed that value, so the configurable part is which one arrives here.
        ClientAddress c = resolver("10.0.0.5", "CF-Connecting-IP");
        assertThat(c.settings.get().effectiveClientIpHeader()).isEqualTo("CF-Connecting-IP");
        assertThat(c.resolve("10.0.0.5", "198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    void aBareAddressInTheTrustedListMeansExactlyThatAddress() {
        ClientAddress c = resolver("10.0.0.5", "X-Forwarded-For");
        assertThat(c.isTrusted("10.0.0.5")).isTrue();
        assertThat(c.isTrusted("10.0.0.6")).isFalse();
    }

    @Test
    void portsAndBracketsAreStripped() {
        assertThat(ClientAddress.normalise(" 1.2.3.4:5678 ")).isEqualTo("1.2.3.4");
        assertThat(ClientAddress.normalise("[2001:db8::1]:443")).isEqualTo("2001:db8::1");
        assertThat(ClientAddress.normalise("2001:db8::1")).isEqualTo("2001:db8::1");
    }
}
