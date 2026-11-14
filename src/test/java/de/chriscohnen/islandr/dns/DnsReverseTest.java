package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** dns-reverse-ptr: PTR answers for tunnel addresses, same names and rules as forward. */
@QuarkusTest
class DnsReverseTest {

    private static final String ZONE = "islandr.internal";
    private static final AtomicInteger N = new AtomicInteger(1);

    @Inject DnsQueryHandler handler;

    @BeforeEach
    @AfterEach
    @Transactional
    void reset() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = false;
        s.dnsResolverZone = null;
        s.dnsResolveAllResourcesAndPeers = false;
        s.wgSubnet = "10.8.0.0/24";
    }

    @Transactional
    void enable(boolean resolveAll) {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        s.dnsResolveAllResourcesAndPeers = resolveAll;
        s.wgSubnet = "10.198.0.0/16";
    }

    private static String ip() {
        int n = N.getAndIncrement();
        return "10.198." + (n / 250) + "." + (1 + n % 250);
    }

    private static String arpa(String ip) {
        String[] o = ip.split("\\.");
        return o[3] + "." + o[2] + "." + o[1] + "." + o[0] + ".in-addr.arpa";
    }

    private static String key(String x, String tag) {
        return ("KEY" + tag + x).repeat(4).substring(0, 43) + "=";
    }

    @Transactional
    Peer peer(String name, String ip) {
        String x = UUID.randomUUID().toString().substring(0, 8);
        User u = User.createNew("Rev " + x, "rev-" + x + "@firma.de");
        u.persist();
        Peer p = Peer.createNew(u.id, name + "-" + x, key(x, "p"), ip);
        p.persist();
        return p;
    }

    private static String target(DnsQueryHandler.Resolution r) {
        assertThat(r).isInstanceOf(DnsQueryHandler.Resolution.Answer.class);
        return ((DnsQueryHandler.Resolution.Answer) r).fqdn();
    }

    @Test
    void peerAddress_answersWithTheForwardName() {
        enable(true);
        String ip = ip();
        Peer p = peer("revpeer", ip);

        String name = target(handler.resolveReverse(arpa(ip), "192.0.2.1"));

        assertThat(name).isEqualTo(handler.peerDnsFqdns(List.of(p)).get(p.id));
        assertThat(target(handler.resolve(name, "192.0.2.1"))).isEqualTo(name);
    }

    @Test
    void hiddenPeer_isNxDomain_notForwarded() {
        enable(false);
        String ip = ip();
        peer("hidden", ip);
        Peer stranger = peer("stranger", ip());

        assertThat(handler.resolveReverse(arpa(ip), stranger.assignedIp))
                .isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    void unknownAddressInTunnelSubnet_isNxDomain_outsideGoesUpstream() {
        enable(true);

        assertThat(handler.resolveReverse(arpa(ip()), "192.0.2.1"))
                .isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
        assertThat(handler.resolveReverse(arpa("8.8.8.8"), "192.0.2.1"))
                .isInstanceOf(DnsQueryHandler.Resolution.NotManaged.class);
    }

    @Test
    void hubAddress_answersHubName() {
        enable(false);

        assertThat(target(handler.resolveReverse(arpa("10.198.0.1"), "192.0.2.1"))).isEqualTo("hub." + ZONE);
    }

    @Test
    void resourceAddress_answersMainName_notAnAlias() {
        enable(true);
        String x = UUID.randomUUID().toString().substring(0, 8);
        Site site = tx(() -> {
            Site st = Site.createNew("RevSite-" + x, "172.30.0.0/24", null);
            st.persist();
            Resource r = Resource.createNew(st.id, "VM-" + x, "172.30.0.9", null, "computer");
            r.dnsName = "main-" + x;
            r.dnsAliases.add("wiki-" + x);
            r.persist();
            return st;
        });

        String name = target(handler.resolveReverse(arpa("172.30.0.9"), "192.0.2.1"));

        assertThat(name).isEqualTo("main-" + x + "." + DnsQueryHandler.slugify(site.name) + "." + ZONE);
    }

    @Test
    void resourceAddress_notVisible_isNxDomain() {
        enable(false);
        String x = UUID.randomUUID().toString().substring(0, 8);
        tx(() -> {
            Site st = Site.createNew("RevHid-" + x, "172.31.0.0/24", null);
            st.persist();
            Resource r = Resource.createNew(st.id, "VM-" + x, "172.31.0.9", null, "computer");
            r.dnsName = "hid-" + x;
            r.persist();
            return st;
        });

        assertThat(handler.resolveReverse(arpa("172.31.0.9"), "192.0.2.1"))
                .isInstanceOf(DnsQueryHandler.Resolution.NxDomain.class);
    }

    @Test
    void ipv6Name_roundTrips() {
        String name = DnsQueryHandler.reverseName("fd00::1234");
        assertThat(name).endsWith(".ip6.arpa").startsWith("4.3.2.1.");
        assertThat(DnsQueryHandler.parseReverseName(name).getHostAddress()).isEqualTo("fd00:0:0:0:0:0:0:1234");
    }

    @Test
    void malformedArpaName_isNotManaged() {
        enable(true);
        assertThat(handler.resolveReverse("10.in-addr.arpa", "192.0.2.1"))
                .isInstanceOf(DnsQueryHandler.Resolution.NotManaged.class);
    }

    @Transactional
    <T> T tx(java.util.function.Supplier<T> body) {
        return body.get();
    }
}
