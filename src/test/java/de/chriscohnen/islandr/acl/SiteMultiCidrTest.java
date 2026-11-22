package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.firewall.RuleBuilder;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * site-multi-cidr (1.5.0, decisions 2026-11-22): an external site carries more than one
 * address range. The primary stays in sites.cidr, further ones in site_extra_cidrs; only
 * external sites may have them.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class SiteMultiCidrTest {

    private static final String MARK = "multicidr";
    private static final String A = "203.0.113.0/28";
    private static final String B = "198.51.100.0/28";
    private static final String C = "192.0.2.0/28";

    @Inject SiteService sites;
    @Inject SettingsService settings;
    @Inject RuleBuilder rules;
    @PersistenceContext EntityManager em;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            RoleNetworkGrant.deleteAll();
            em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
            Role.delete("name like ?1", MARK + "-%");
            User.delete("email", MARK + "@example.test");
            SiteExtraCidr.deleteAll();
            Site.delete("name like ?1", MARK + "-%");
            Peer.delete("name like ?1", MARK + "-%");
        });
    }

    private static SiteDto.UpsertRequest req(String cidr, List<String> extra, String kind) {
        return new SiteDto.UpsertRequest(MARK + "-" + UUID.randomUUID(), cidr, null, null, null, null, null, kind, extra);
    }

    private Site createExternal(String cidr, List<String> extra) {
        return QuarkusTransaction.requiringNew().call(() -> sites.create(req(cidr, extra, Site.KIND_EXTERNAL)));
    }

    private List<String> all(Site s) {
        return QuarkusTransaction.requiringNew().call(() -> ((Site) Site.findById(s.id)).allCidrs());
    }

    @Test
    void anExternalSiteKeepsItsAddressesInOrder_primaryFirst() {
        Site s = createExternal(A, List.of(B, "2001:db8::/64"));
        assertThat(all(s)).containsExactly(A, B, "2001:db8::/64");
        SiteDto.Response r = QuarkusTransaction.requiringNew().call(() -> sites.toResponse(Site.findById(s.id), 0));
        assertThat(r.cidr()).isEqualTo(A);
        assertThat(r.cidrs()).containsExactly(A, B, "2001:db8::/64");
    }

    @Test
    void aSiteWithoutExtras_isUnchanged() {
        Site s = createExternal(A, null);
        assertThat(all(s)).containsExactly(A);
    }

    @Test
    void internalSite_refusesExtras() {
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(
                () -> sites.create(req("10.97.0.0/24", List.of(B), Site.KIND_INTERNAL))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void duplicatesOverlapsAndTooMany_areRefused() {
        assertThatThrownBy(() -> createExternal(A, List.of(A))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> createExternal(A, List.of(B, B))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> createExternal("203.0.113.0/24", List.of("203.0.113.16/28")))
                .isInstanceOf(BadRequestException.class);
        List<String> fifteen = java.util.stream.IntStream.range(0, 15).mapToObj(i -> "198.51." + i + ".0/24").toList();
        assertThat(all(createExternal("203.0.113.0/28", fifteen))).hasSize(16);
        List<String> sixteen = java.util.stream.IntStream.range(0, 16).mapToObj(i -> "198.52." + i + ".0/24").toList();
        assertThatThrownBy(() -> createExternal("203.0.114.0/28", sixteen)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void update_replacesTheExtras_andAnInternalSwitchWithExtrasIsRefused() {
        Site s = createExternal(A, List.of(B));
        QuarkusTransaction.requiringNew().run(() -> sites.update(s.id,
                new SiteDto.UpsertRequest(s.name, A, null, null, null, null, null, Site.KIND_EXTERNAL, List.of(C))));
        assertThat(all(s)).containsExactly(A, C);

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> sites.update(s.id,
                new SiteDto.UpsertRequest(s.name, A, null, null, null, null, null, Site.KIND_INTERNAL, List.of(C)))))
                .isInstanceOf(BadRequestException.class);

        QuarkusTransaction.requiringNew().run(() -> sites.update(s.id,
                new SiteDto.UpsertRequest(s.name, A, null, null, null, null, null, Site.KIND_EXTERNAL, List.of())));
        assertThat(all(s)).containsExactly(A);
    }

    @Test
    void deletingTheSite_takesItsExtrasWithIt() {
        Site s = createExternal(A, List.of(B));
        QuarkusTransaction.requiringNew().run(() -> sites.delete(s.id));
        assertThat(QuarkusTransaction.requiringNew().call(() -> SiteExtraCidr.count("siteId", s.id))).isZero();
    }

    @Test
    void everyAddressIsRoutedToClients() {
        createExternal(A, List.of(B));
        List<String> routed = QuarkusTransaction.requiringNew().call(Site::clientRoutedCidrs);
        assertThat(routed).contains(A, B);
    }

    @Test
    void ruleset_masqueradesAndAdmitsEveryAddress_aV6OneOnlyForAV6Peer() {
        Site s = createExternal(A, List.of(B, "2001:db8::/64"));
        QuarkusTransaction.requiringNew().run(() -> settings.get().wanInterface = "eth0");
        Role role = QuarkusTransaction.requiringNew().call(() -> {
            User u = User.createNew("Multi", MARK + "@example.test");
            u.persist();
            Role r = Role.createNew(MARK + "-role", null);
            r.persist();
            em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                    .setParameter(1, u.id).setParameter(2, r.id).executeUpdate();
            Peer.createNew(u.id, MARK + "-laptop", "MultiCidr0000000000000000000000000000000001=", "10.8.0.241").persist();
            RoleNetworkGrant.createNew(r.id, s.id).persist();
            return r;
        });
        String text = QuarkusTransaction.requiringNew().call(() -> rules.build().rulesetText());
        assertThat(text).contains("ip daddr " + A + " masquerade").contains("ip daddr " + B + " masquerade");
        assertThat(text).contains("ip saddr 10.8.0.241 ip daddr " + A + " accept")
                .contains("ip saddr 10.8.0.241 ip daddr " + B + " accept");
        // a v4-only device gets nothing for the v6 range
        assertThat(text).doesNotContain("ip6 daddr 2001:db8::/64 accept");
    }

    @Test
    void gatewayImport_skipsAnAddressThatIsAlreadyAnExtra() {
        createExternal(A, List.of(B));
        var results = QuarkusTransaction.requiringNew().call(() -> sites.gatewayImport(List.of(
                new SiteDto.GatewayNetworkEntry("no-such-peer", B, "x", null, null))));
        assertThat(results).singleElement().extracting(SiteDto.GatewayImportResult::status).isEqualTo("skipped");
    }

    @Test
    void api_acceptsAndReturnsCidrs() {
        String id = given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-api\",\"cidr\":\"" + A + "\",\"kind\":\"external\",\"extraCidrs\":[\"" + B + "\"]}")
                .post("/api/v1/sites").then().statusCode(201).extract().path("id");
        given().get("/api/v1/sites/" + id).then().statusCode(200)
                .body("cidr", org.hamcrest.Matchers.is(A))
                .body("cidrs", org.hamcrest.Matchers.contains(A, B));
        given().contentType("application/json")
                .body("{\"name\":\"" + MARK + "-bad\",\"cidr\":\"" + A + "\",\"kind\":\"external\",\"extraCidrs\":[\"not-a-cidr\"]}")
                .post("/api/v1/sites").then().statusCode(400);
    }
}
