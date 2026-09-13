package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #80: which addresses may speak for a client is a runtime setting, not
 * a properties file. A reverse proxy is an operational fact like TLS, and an
 * operator who gets it wrong — and so bans their own proxy — should not need a
 * service restart to fix it.
 *
 * <p>The value is security-relevant in an unusual direction: anything listed
 * here may claim to be any client, so a bad entry has to be refused with a
 * message naming it, rather than silently dropped and discovered later as a
 * ban on the wrong address.
 */
@QuarkusTest
class TrustedProxySettingsTest {

    @Inject SettingsService settings;
    @Inject ClientAddress clientAddress;

    /** Never leave a trust entry behind for another test class to inherit. */
    @AfterEach
    void clear() {
        setTrusted(null);
    }

    /** A bad entry is named, not swallowed. */
    @Test
    void anInvalidEntryIsRefusedByName() {
        assertThatThrownBy(() -> ClientAddress.validate("127.0.0.1, not-an-address"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-an-address");
    }

    @Test
    void blankMeansNobody() {
        assertThat(ClientAddress.validate(null)).isNull();
        assertThat(ClientAddress.validate("   ")).isNull();
        assertThat(ClientAddress.validate(",, ,")).isNull();
    }

    @Test
    void bareAddressesAndCidrsAreBothAccepted() {
        assertThat(ClientAddress.validate("127.0.0.1, ::1, 10.0.0.0/8"))
                .isEqualTo("127.0.0.1,::1,10.0.0.0/8");
    }

    /**
     * The resolver must follow a change without a restart — that is the whole
     * reason this moved out of application.properties.
     */
    /**
     * One transaction, one bean, three different answers: the resolver reads
     * the setting per request rather than caching it for the process lifetime.
     * ({@code @Transactional} sits on the test method itself — a helper called
     * from here would be self-invocation and the interceptor would not run.)
     */
    @Test
    @jakarta.transaction.Transactional
    void aChangedSettingTakesEffectImmediately() {
        Settings s = settings.get();

        s.trustedProxies = null;
        assertThat(clientAddress.resolve("10.0.0.5", "198.51.100.7"))
                .as("nothing trusted: the socket peer wins")
                .isEqualTo("10.0.0.5");

        s.trustedProxies = "10.0.0.0/24";
        assertThat(clientAddress.resolve("10.0.0.5", "198.51.100.7"))
                .as("same instance, no restart")
                .isEqualTo("198.51.100.7");

        s.trustedProxies = null;
        assertThat(clientAddress.resolve("10.0.0.5", "198.51.100.7"))
                .as("and back again")
                .isEqualTo("10.0.0.5");
    }

    @jakarta.transaction.Transactional
    void setTrusted(String value) {
        settings.get().trustedProxies = value;
    }

    /** Unset means X-Forwarded-For, so an upgrade changes nothing. */
    @Test
    void theHeaderDefaultsToXForwardedFor() {
        Settings s = new Settings();
        assertThat(s.effectiveClientIpHeader()).isEqualTo("X-Forwarded-For");
        s.clientIpHeader = "  CF-Connecting-IP ";
        assertThat(s.effectiveClientIpHeader()).isEqualTo("CF-Connecting-IP");
    }
}
