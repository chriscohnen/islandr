package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.firewall.RuleBuilder;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * egress-via-hub (1.4.0, decision 2026-11-14, variant A): a partner system that
 * only admits fixed source addresses is reached through the hub's own public
 * address. It is modelled as a site of kind "external" — its public network as
 * the CIDR, no gateway peer — with resources and grants as usual. What the
 * kind adds: the CIDR is routed to clients, the hub masquerades exactly that
 * traffic out of its WAN interface, and discovery never scans it.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class ExternalSiteEgressTest {

    private static final String MARK = "egress";
    private static final String PARTNER_CIDR = "203.0.113.0/28";

    @Inject SiteService sites;
    @Inject SettingsService settings;
    @Inject RuleBuilder rules;
    @PersistenceContext EntityManager em;

    private String savedWan;

    @BeforeEach
    void remember() {
        savedWan = QuarkusTransaction.requiringNew().call(() -> settings.get().wanInterface);
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            RoleNetworkGrant.deleteAll();
            em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
            RoleResourceGrant.deleteAll();
            em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
            ResourcePort.deleteAll();
            Resource.delete("name like ?1", MARK + "-%");
            Role.delete("name like ?1", MARK + "-%");
            User.delete("email", MARK + "@example.test");
            Site.delete("name like ?1", MARK + "-%");
            Peer.delete("name like ?1", MARK + "-%");
            settings.get().wanInterface = savedWan;
        });
    }

    private Site external(String cidr) {
        return QuarkusTransaction.requiringNew().call(() -> sites.create(new SiteDto.UpsertRequest(
                MARK + "-" + UUID.randomUUID(), cidr, null, null, null, null, null, Site.KIND_EXTERNAL)));
    }

    private void wan(String iface) {
        QuarkusTransaction.requiringNew().run(() -> settings.get().wanInterface = iface);
    }

    @Test
    void siteKind_defaultsToInternal() {
        Site s = QuarkusTransaction.requiringNew().call(() -> sites.create(new SiteDto.UpsertRequest(
                MARK + "-" + UUID.randomUUID(), "10.91.0.0/24", null, null, null, null, null, null)));
        assertThat(s.kind).isEqualTo(Site.KIND_INTERNAL);
    }

    @Test
    void externalSite_refusesAGatewayPeer() {
        String gw = QuarkusTransaction.requiringNew().call(() -> {
            Peer p = Peer.createNew(null, MARK + "-gw", "EgressGw0000000000000000000000000000000001=", "10.8.0.230");
            p.type = "site";
            p.persist();
            return p.id;
        });
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> sites.create(new SiteDto.UpsertRequest(
                MARK + "-" + UUID.randomUUID(), PARTNER_CIDR, null, gw, null, null, null, Site.KIND_EXTERNAL))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void externalCidr_isRoutedToClients_internalWithoutGateway_isNot() {
        external(PARTNER_CIDR);
        assertThat(QuarkusTransaction.requiringNew().call(Site::clientRoutedCidrs)).contains(PARTNER_CIDR);
    }

    @Test
    void ruleset_masqueradesExactlyTheExternalCidr_outOfTheWanInterface() {
        external(PARTNER_CIDR);
        wan("eth0");
        String text = QuarkusTransaction.requiringNew().call(() -> rules.build().rulesetText());
        String net = QuarkusTransaction.requiringNew().call(() -> {
            Settings s = settings.get();
            var subnet = de.chriscohnen.islandr.peer.IpSubnet.parse(s.wgSubnet);
            return subnet.networkAddress() + "/" + subnet.prefix();
        });
        assertThat(text).contains("chain postrouting")
                .contains("type nat hook postrouting priority srcnat; policy accept;")
                .contains("oifname \"eth0\" ip saddr " + net + " ip daddr " + PARTNER_CIDR + " masquerade");
    }

    @Test
    void ruleset_hasNoNatChain_withoutExternalSites() {
        wan("eth0");
        String text = QuarkusTransaction.requiringNew().call(() -> rules.build().rulesetText());
        assertThat(text).doesNotContain("postrouting").doesNotContain("masquerade");
    }

    @Test
    void ruleset_withoutAKnownWanInterface_masqueradesNothing() {
        external(PARTNER_CIDR);
        wan(null);
        // The test JVM has no /proc/net/route to detect one from; with no WAN
        // interface known there is nothing safe to masquerade out of.
        String text = QuarkusTransaction.requiringNew().call(() -> rules.build().rulesetText());
        assertThat(text).doesNotContain("masquerade");
    }

    @Test
    void discovery_neverScansAnExternalSite() {
        Site s = external(PARTNER_CIDR);
        given().contentType("application/json")
                .when().post("/api/v1/sites/" + s.id + "/discovery/scan?force=true")
                .then().statusCode(409);
    }

    @Test
    void wanInterfaceOverride_refusesAnythingThatIsNotAnInterfaceName() {
        // The name lands verbatim in nftables text — a quote would end the
        // oifname string and let the rest become rules.
        given().contentType("application/json")
                .body("{\"wanInterface\":\"eth0\\\" accept; #\"}")
                .when().put("/api/v1/settings/wan-interface")
                .then().statusCode(400);
        given().contentType("application/json")
                .body("{\"wanInterface\":\"ens3\"}")
                .when().put("/api/v1/settings/wan-interface")
                .then().statusCode(200)
                .body("wanInterface", org.hamcrest.Matchers.equalTo("ens3"));
    }

    /** One user, one role, one client peer; the role is granted whatever the caller adds. */
    private Role roleWithPeer(String peerName) {
        return QuarkusTransaction.requiringNew().call(() -> {
            User u = User.createNew("Egress", MARK + "@example.test");
            u.persist();
            Role r = Role.createNew(MARK + "-role", null);
            r.persist();
            em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                    .setParameter(1, u.id).setParameter(2, r.id).executeUpdate();
            Peer.createNew(u.id, peerName, "EgressLog00000000000000000000000000000001=", "10.8.0.231").persist();
            return r;
        });
    }

    private void grantPort(Role role, Site site, String ip, int port) {
        QuarkusTransaction.requiringNew().run(() -> {
            Resource res = Resource.createNew(site.id, MARK + "-" + UUID.randomUUID(), ip, null, "server");
            res.persist();
            ResourcePort.createNew(res.id, port, null, "tcp", "TCP", null, null, true, false, "native").persist();
            RoleResourceGrant.createNew(role.id, res.id, true).persist();
        });
    }

    @Test
    void egressAudit_logsNewConnectionsToExternalTargetsOnly_rateLimited_beforeTheAccept() {
        Role role = roleWithPeer(MARK + "-laptop");
        Site partner = external(PARTNER_CIDR);
        Site lan = QuarkusTransaction.requiringNew().call(() -> sites.create(new SiteDto.UpsertRequest(
                MARK + "-lan", "10.92.0.0/24", null, null, null, null, null, null)));
        grantPort(role, partner, "203.0.113.5", 443);
        grantPort(role, lan, "10.92.0.5", 22);

        String text = QuarkusTransaction.requiringNew().call(() -> rules.build().rulesetText());

        String logLine = "ip daddr 203.0.113.5 tcp dport 443 ct state new limit rate 1/minute "
                + "log prefix \"islandr-egress " + MARK + "-laptop \"";
        assertThat(text).contains(logLine);
        assertThat(text.indexOf(logLine)).isLessThan(text.indexOf("ip daddr 203.0.113.5 tcp dport 443 accept"));
        // An internal target is not the partner's business: no log there.
        assertThat(text).contains("ip daddr 10.92.0.5 tcp dport 22 accept")
                .doesNotContain("ip daddr 10.92.0.5 tcp dport 22 ct state new");
    }

    @Test
    void egressAudit_prefixCannotBreakOutOfTheQuotes_andStaysShort() {
        Role role = roleWithPeer(MARK + "-\"; drop #" + "x".repeat(200));
        grantPort(role, external(PARTNER_CIDR), "203.0.113.6", 443);

        String text = QuarkusTransaction.requiringNew().call(() -> rules.build().rulesetText());

        String line = text.lines().filter(l -> l.contains("log prefix")).findFirst().orElseThrow();
        String prefix = line.substring(line.indexOf("log prefix \"") + 12, line.lastIndexOf('"'));
        assertThat(prefix).doesNotContain("\"").startsWith("islandr-egress ");
        assertThat(prefix.length()).isLessThanOrEqualTo(127);
        // iifname "wg0" and the prefix: two pairs, nothing from the name
        assertThat(line.chars().filter(c -> c == '"').count()).isEqualTo(4);
    }

    @Test
    void egressAudit_coversAWholeNetworkGrantToAnExternalSite() {
        Role role = roleWithPeer(MARK + "-net");
        Site partner = external(PARTNER_CIDR);
        QuarkusTransaction.requiringNew().run(() -> RoleNetworkGrant.createNew(role.id, partner.id).persist());

        String text = QuarkusTransaction.requiringNew().call(() -> rules.build().rulesetText());

        assertThat(text).contains("ip daddr " + PARTNER_CIDR + " ct state new limit rate 1/minute "
                + "log prefix \"islandr-egress " + MARK + "-net \"");
    }
}
