package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.acl.ResourceDto;
import de.chriscohnen.islandr.acl.ResourceService;
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

import static org.assertj.core.api.Assertions.assertThat;

/** dns-ipv6-and-query-types: A/AAAA selection, dual stack, NODATA for other types. */
@QuarkusTest
class DnsAddressFamilyTest {

    private static final String ZONE = "islandr.internal";

    @Inject DnsQueryHandler handler;
    @Inject ResourceService resources;

    @BeforeEach
    @AfterEach
    @Transactional
    void reset() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = false;
        s.dnsResolverZone = null;
        s.dnsResolveAllResourcesAndPeers = false;
    }

    @Test
    void addressFor_picksTheFamilyOfTheQuestion_andNothingForOtherTypes() {
        assertThat(DnsResolverService.addressFor(1, "10.0.0.5", "fd00::5")).hasSize(4);
        assertThat(DnsResolverService.addressFor(28, "10.0.0.5", "fd00::5")).hasSize(16);
        assertThat(DnsResolverService.addressFor(28, "10.0.0.5", null)).isNull();
        assertThat(DnsResolverService.addressFor(1, "fd00::5", null)).isNull();
        assertThat(DnsResolverService.addressFor(28, "fd00::5", null)).hasSize(16);
        assertThat(DnsResolverService.addressFor(65, "10.0.0.5", "fd00::5")).isNull(); // HTTPS
    }

    @Test
    @Transactional
    void dualStackResource_carriesBothAddresses_inTheAnswer() {
        String x = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        Site site = Site.createNew("DualSite-" + x, "10.96.0.0/24", null);
        site.persist();
        resources.create(site.id, new ResourceDto.UpsertRequest("VM-" + x, "10.96.0.5", null, "computer",
                "dual-" + x, true, null, null, "fd96::5"));

        var a = (DnsQueryHandler.Resolution.Answer) handler.resolveForAdminPreview("dual-" + x + "." + ZONE);

        assertThat(a.ip()).isEqualTo("10.96.0.5");
        assertThat(a.ip6()).isEqualTo("fd96::5");
    }

    @Test
    @Transactional
    void ptrOnTheIpv6Address_answersTheMainName() {
        String x = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        s.dnsResolveAllResourcesAndPeers = true;
        Site site = Site.createNew("Dual6-" + x, "10.97.0.0/24", null);
        site.persist();
        resources.create(site.id, new ResourceDto.UpsertRequest("VM-" + x, "10.97.0.5", null, "computer",
                "d6-" + x, true, null, null, "fd97::5"));

        var r = handler.resolveReverseForAdminPreview("fd97::5");

        assertThat(((DnsQueryHandler.Resolution.Answer) r).fqdn()).isEqualTo("d6-" + x + "." + ZONE);
    }

    @Test
    @Transactional
    void peerWithIpv6_answersItsIpv6Address() {
        String x = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        User u = User.createNew("V6 " + x, "v6-" + x + "@firma.de");
        u.persist();
        Peer p = Peer.createNew(u.id, "v6peer-" + x, ("KEYv" + x).repeat(6).substring(0, 43) + "=", "10.98.0." + (1 + (int) (Math.random() * 200)));
        p.assignedIpv6 = "fd98::" + Integer.toHexString(1 + (int) (Math.random() * 60000));
        p.persist();
        String fqdn = handler.peerDnsFqdns(List.of(p)).get(p.id);

        var a = (DnsQueryHandler.Resolution.Answer) handler.resolveForAdminPreview(fqdn);

        assertThat(a.ip6()).isEqualTo(p.assignedIpv6);
    }

    @Test
    @Transactional
    void resourceNames_listsMainNameFirst() {
        String x = UUID.randomUUID().toString().substring(0, 8);
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
        Site site = Site.createNew("Names-" + x, "10.99.0.0/24", null);
        site.persist();
        Resource r = resources.create(site.id, new ResourceDto.UpsertRequest("VM-" + x, "10.99.0.5", null, "computer",
                "zz-main-" + x, true, null, List.of("aa-alias-" + x)));

        assertThat(handler.resourceNames(r.id)).containsExactly(
                "zz-main-" + x + "." + ZONE, "aa-alias-" + x + "." + ZONE);
    }
}
