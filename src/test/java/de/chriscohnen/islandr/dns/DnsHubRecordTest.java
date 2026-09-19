package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.peer.IpSubnet;
import de.chriscohnen.islandr.settings.Settings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hub answering for its own name. Without it, reaching the admin console
 * on an installation that exposes nothing means typing a tunnel IP or editing
 * /etc/hosts on every device.
 *
 * <p>The record deliberately skips the grant check that guards resource names.
 * That check exists so a name never points at a host nftables is about to drop
 * packets for — it does not apply here, because the ruleset filters *forwarded*
 * traffic and every peer reaches the hub itself regardless. Refusing the name
 * would protect nothing and would break it for site gateways, which have no
 * owning user at all.
 */
@QuarkusTest
class DnsHubRecordTest {

    @Inject DnsQueryHandler handler;

    private static final String ZONE = "islandr.internal";

    /** Read, never written: Settings is one shared row for the whole suite, and
     *  overwriting wgSubnet here would re-address every peer another test
     *  allocates afterwards. The hub address is derived from whatever the row
     *  already holds, by the same network+1 convention the peer configs use. */
    private String hubIp;

    @AfterEach
    @Transactional
    void reset() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = false;
        s.dnsResolverZone = null;
        s.dnsHubAlias = null;
    }

    @Transactional
    void enableResolver(String alias) {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        s.dnsHubAlias = alias;
        hubIp = IpSubnet.parse(s.wgSubnet).networkAddress();
    }

    /** An address inside the hub's own subnet, so the query looks like it came
     *  through the tunnel. Which address does not matter for the hub record —
     *  that is the point of it. */
    private String aPeerAddress() {
        return hubIp;
    }

    @Test
    void hubResolvesToItsTunnelAddress() {
        enableResolver(null);

        assertThat(handler.resolve("hub." + ZONE, aPeerAddress()))
                .isEqualTo(new DnsQueryHandler.Resolution.Answer(hubIp, "hub." + ZONE, null));
    }

    @Test
    void hubAnswersAPeerWithNoOwningUser() {
        enableResolver(null);

        // A source address belonging to no peer at all — the strictest case
        // the resource path refuses. A site gateway (no userId) is the real
        // one, and it fails the same check.
        assertThat(handler.resolve("hub." + ZONE, "192.0.2.250"))
                .as("every peer reaches the hub regardless; refusing its name protects nothing")
                .isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
    }

    @Test
    void theAliasIsAnsweredEvenThoughItSitsOutsideTheManagedZone() {
        enableResolver("konsole.firma.de");

        assertThat(handler.resolve("konsole.firma.de", aPeerAddress()))
                .as("answering it locally is what keeps the console reachable when the upstream resolver is not")
                .isEqualTo(new DnsQueryHandler.Resolution.Answer(hubIp, "konsole.firma.de", null));
    }

    @Test
    void anUnsetAliasChangesNothing() {
        enableResolver(null);

        assertThat(handler.resolve("konsole.firma.de", aPeerAddress()))
                .isInstanceOf(DnsQueryHandler.Resolution.NotManaged.class);
    }

    @Test
    void withTheResolverOffTheHubNameIsNotOurs() {
        enableResolver(null);
        disable();

        assertThat(handler.resolve("hub." + ZONE, aPeerAddress()))
                .isInstanceOf(DnsQueryHandler.Resolution.NotManaged.class);
    }

    @Test
    void theAdminPreviewShowsTheSameAnswerAPeerWouldGet() {
        enableResolver(null);

        assertThat(handler.resolveForAdminPreview("hub." + ZONE))
                .as("an NXDOMAIN here would only mean the preview does not know about the record")
                .isEqualTo(new DnsQueryHandler.Resolution.Answer(hubIp, "hub." + ZONE, null));
    }

    @Transactional
    void disable() {
        Settings.<Settings>findById(Settings.SINGLETON_ID).dnsResolverEnabled = false;
    }
}
