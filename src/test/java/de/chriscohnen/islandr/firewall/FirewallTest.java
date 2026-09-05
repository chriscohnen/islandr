package de.chriscohnen.islandr.firewall;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.ResourcePort;
import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleNetworkGrant;
import de.chriscohnen.islandr.acl.RoleResourceGrant;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.acl.UserResourceGrant;
import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the firewall stack:
 * - RuleBuilder produces the expected nftables text from a known fixture
 * - RulesetService updates FirewallState on apply success/failure
 * - FirewallResource exposes status + resync correctly
 * - The mock adapter receives an apply call when an ACL change happens
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class FirewallTest {

    @Inject RuleBuilder builder;
    @Inject RulesetService rulesets;
    @Inject NftablesAdapter adapter;
    @Inject de.chriscohnen.islandr.acl.RoleBootstrap roleBootstrap;
    @PersistenceContext EntityManager em;

    /**
     * Quarkus / Arc wraps {@code @ApplicationScoped} producers behind a
     * client proxy, so a direct cast to {@link MockNftablesAdapter} fails.
     * Unwrap once to get the real instance for state assertions.
     */
    private MockNftablesAdapter mock() {
        return (MockNftablesAdapter) io.quarkus.arc.ClientProxy.unwrap(adapter);
    }

    @BeforeEach
    @Transactional
    void seed() {
        wipeAclRows();
        // Reset FirewallState back to 'never' so apply assertions are clean.
        FirewallState s = FirewallState.get();
        s.lastStatus = FirewallState.NEVER;
        s.lastAttemptAt = null;
        s.lastOkAt = null;
        s.ruleCount = 0;
        s.rulesetText = null;
        s.stderrText = null;
        // Reset the mock so apply-count comparisons start at 0.
        mock().resetForTests();
    }

    // Without this, the last test method's rows (e.g. a Peer hardcoded at
    // "10.8.0.5") stay in the DB after this class finishes and can collide
    // with an unrelated test elsewhere in the suite — the shared %test
    // datasource has no automatic per-class isolation.
    @AfterEach
    @Transactional
    void teardown() {
        wipeAclRows();
        // Role.deleteAll() above also removes the RoleBootstrap-seeded
        // "Everyone" auto_all role. Reseed only here, after the class's own
        // tests are done (some of them deliberately create their own
        // "Everyone" role mid-test, which would collide with the unique
        // roles.name index if this ran in @BeforeEach too) — so whichever
        // test class runs next still finds the invariant "exactly one
        // auto_all role always exists" holding. Its absence otherwise flakes
        // ConfigImportRoundTripTest depending on suite execution order.
        roleBootstrap.seedEveryoneRole();
    }

    private void wipeAclRows() {
        // Wipe everything ACL-related so each test starts from a known state.
        em.createNativeQuery("DELETE FROM user_resource_grant_ports").executeUpdate();
        UserResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM site_resource_grant_ports").executeUpdate();
        de.chriscohnen.islandr.acl.SiteResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
        RoleResourceGrant.deleteAll();
        de.chriscohnen.islandr.acl.RoleResourceTypeGrant.deleteAll();
        RoleNetworkGrant.deleteAll();
        em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        Peer.deleteAll();
        AuditLog.deleteAll();
    }

    // -- RuleBuilder ---------------------------------------------------------

    @Test
    @Transactional
    void ruleBuilder_emptyDb_returnsEmptyTable() {
        RuleBuilder.Snapshot snap = builder.build();
        assertThat(snap.ruleCount()).isZero();
        // Even an empty system produces a table — it just has the chain
        // skeleton with policy=drop and no accepts.
        assertThat(snap.rulesetText()).contains("table inet islandr");
        assertThat(snap.rulesetText()).contains("policy drop");
        assertThat(snap.rulesetText()).contains("flush table inet islandr");
        // Conntrack rules must always be present so return traffic is not dropped.
        assertThat(snap.rulesetText()).contains("ct state established,related accept");
        assertThat(snap.rulesetText()).contains("ct state invalid drop");
    }

    @Test
    @Transactional
    void ruleBuilder_conntrackRulesPrecedePerPeerRules() {
        User u = persistUser("conntrack@example.test", "CT User");
        Role role = persistRole("CTRole");
        addUserToRole(u.id, role.id);
        Site site = persistSite("CTSite", "10.40.0.0/16");
        Resource res = persistResource(site.id, "Server", "10.40.0.1");
        ResourcePort port = persistPort(res.id, 22, "tcp", "SSH");
        RoleResourceGrant grant = RoleResourceGrant.createNew(role.id, res.id, false);
        grant.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, grant.id).setParameter(2, port.id).executeUpdate();
        persistPeer(u.id, "ct-peer", "10.8.0.50");

        String text = builder.build().rulesetText();
        int conntrackPos = text.indexOf("ct state established,related accept");
        int peerRulePos  = text.indexOf("ip saddr 10.8.0.50");
        assertThat(conntrackPos).isGreaterThan(0);
        assertThat(conntrackPos).isLessThan(peerRulePos);
    }

    @Test
    @Transactional
    void ruleBuilder_oneGrantOnePortOnePeer_producesOneRuleWithComment() {
        // Set up: one user in one role, one site/resource/port, one grant on
        // that resource for that role, one peer for that user.
        User user = persistUser("alice@example.test", "Alice");
        Role role = persistRole("Vertrieb");
        addUserToRole(user.id, role.id);
        Site site = persistSite("HQ", "10.20.0.0/16");
        Resource res = persistResource(site.id, "Terminal-01", "10.20.0.5");
        ResourcePort port = persistPort(res.id, 3389, "tcp", "RDP");
        RoleResourceGrant grant = RoleResourceGrant.createNew(role.id, res.id, false);
        grant.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, grant.id).setParameter(2, port.id).executeUpdate();
        persistPeer(user.id, "macbook", "10.8.0.5");

        RuleBuilder.Snapshot snap = builder.build();

        // 1 TCP rule + 1 implicit ICMP rule
        assertThat(snap.ruleCount()).isEqualTo(2);
        // Rule format: iifname "wg0" ip saddr 10.8.0.5 ip daddr 10.20.0.5 tcp dport 3389 accept comment "..."
        assertThat(snap.rulesetText())
                .contains("ip saddr 10.8.0.5")
                .contains("ip daddr 10.20.0.5")
                .contains("tcp dport 3389")
                .contains("icmp type echo-request")
                .contains("accept");
        // Audit-friendly comment names role, peer, user, resource, protocol.
        assertThat(snap.rulesetText())
                .contains("role=Vertrieb")
                .contains("peer=macbook")
                .contains("user=Alice")
                .contains("resource=Terminal-01")
                .contains("RDP");
    }

    @Test
    @Transactional
    void ruleBuilder_allPortsGrant_emitsOneRulePerResourcePort() {
        User u = persistUser("bob@example.test", "Bob");
        Role role = persistRole("IT");
        addUserToRole(u.id, role.id);
        Site site = persistSite("DC", "10.21.0.0/16");
        Resource res = persistResource(site.id, "Switch-1", "10.21.0.10");
        persistPort(res.id, 22, "tcp", "SSH");
        persistPort(res.id, 443, "tcp", "HTTPS");
        // all_ports=true means: every current and future port of the resource.
        RoleResourceGrant grant = RoleResourceGrant.createNew(role.id, res.id, true);
        grant.persist();
        persistPeer(u.id, "ipad", "10.8.0.6");

        RuleBuilder.Snapshot snap = builder.build();
        // 2 TCP rules + 1 implicit ICMP rule (deduplicated per peer/resource pair)
        assertThat(snap.ruleCount()).isEqualTo(3);
        assertThat(snap.rulesetText())
                .contains("tcp dport 22")
                .contains("tcp dport 443")
                .contains("icmp type echo-request");
    }

    @Test
    @Transactional
    void ruleBuilder_typeGrant_coversMatchingResourceInThatSiteOnly() {
        // "All printers in Homeoffice" (ACL type-grants, 2026-07-28): a
        // type-grant with no concrete RoleResourceGrant row must still
        // produce enforcement rules for a matching resource, and must NOT
        // apply to a same-typed resource in a different site.
        User u = persistUser("carol@example.test", "Carol");
        Role role = persistRole("Printing");
        addUserToRole(u.id, role.id);
        Site home = persistSite("Homeoffice", "10.22.0.0/16");
        Site office = persistSite("Office", "10.23.0.0/16");
        Resource homePrinter = de.chriscohnen.islandr.acl.Resource.createNew(
                home.id, "LaserJet", "10.22.0.5", null, "printer");
        homePrinter.persist();
        Resource officePrinter = de.chriscohnen.islandr.acl.Resource.createNew(
                office.id, "OfficeJet", "10.23.0.5", null, "printer");
        officePrinter.persist();
        persistPort(homePrinter.id, 631, "tcp", "IPP");
        persistPort(officePrinter.id, 631, "tcp", "IPP");
        de.chriscohnen.islandr.acl.RoleResourceTypeGrant.createNew(role.id, home.id, "printer").persist();
        persistPeer(u.id, "laptop", "10.8.0.7");

        RuleBuilder.Snapshot snap = builder.build();

        assertThat(snap.rulesetText())
                .contains("ip daddr 10.22.0.5")
                .contains("tcp dport 631");
        assertThat(snap.rulesetText()).doesNotContain("ip daddr 10.23.0.5");
    }

    @Test
    @Transactional
    void ruleBuilder_disabledPeer_producesNoRule() {
        User u = persistUser("eve@example.test", "Eve");
        Role role = persistRole("Guest");
        addUserToRole(u.id, role.id);
        Site site = persistSite("Office", "10.22.0.0/16");
        Resource res = persistResource(site.id, "X", "10.22.0.5");
        ResourcePort port = persistPort(res.id, 22, "tcp", "SSH");
        RoleResourceGrant g = RoleResourceGrant.createNew(role.id, res.id, false);
        g.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, g.id).setParameter(2, port.id).executeUpdate();
        Peer p = persistPeer(u.id, "phone", "10.8.0.7");
        p.enabled = false;

        RuleBuilder.Snapshot snap = builder.build();
        assertThat(snap.ruleCount()).isZero();
    }

    @Test
    @Transactional
    void ruleBuilder_neverEmitsFlushRuleset() {
        // Critical safety property: we must never destroy other tables.
        // The output is allowed to flush our own table; nothing else.
        RuleBuilder.Snapshot snap = builder.build();
        assertThat(snap.rulesetText())
                .doesNotContain("flush ruleset")
                .contains("flush table inet islandr");
    }

    @Test
    @Transactional
    void ruleBuilder_directUserGrant_producesRuleForEveryPeerOfThatUser() {
        User user = persistUser("dana@example.test", "Dana");
        // Deliberately no role/membership — a direct grant must not depend on it.
        Site site = persistSite("Direct", "10.41.0.0/16");
        Resource res = persistResource(site.id, "FileShare", "10.41.0.7");
        ResourcePort port = persistPort(res.id, 445, "tcp", "SMB");
        de.chriscohnen.islandr.acl.UserResourceGrant grant =
                de.chriscohnen.islandr.acl.UserResourceGrant.createNew(user.id, res.id, false);
        grant.persist();
        em.createNativeQuery("INSERT INTO user_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, grant.id).setParameter(2, port.id).executeUpdate();
        persistPeer(user.id, "dana-laptop", "10.8.0.60");
        persistPeer(user.id, "dana-phone", "10.8.0.61");

        String text = builder.build().rulesetText();

        assertThat(text)
                .contains("ip saddr 10.8.0.60")
                .contains("ip saddr 10.8.0.61")
                .contains("ip daddr 10.41.0.7")
                .contains("tcp dport 445")
                .contains("resource=FileShare");
    }

    @Test
    @Transactional
    void ruleBuilder_directUserGrant_allPorts_emitsOneRulePerResourcePort() {
        User user = persistUser("frank@example.test", "Frank");
        Site site = persistSite("DirectAllPorts", "10.42.0.0/16");
        Resource res = persistResource(site.id, "Multi", "10.42.0.9");
        persistPort(res.id, 80, "tcp", "HTTP");
        persistPort(res.id, 443, "tcp", "HTTPS");
        de.chriscohnen.islandr.acl.UserResourceGrant.createNew(user.id, res.id, true).persist();
        persistPeer(user.id, "frank-desktop", "10.8.0.62");

        RuleBuilder.Snapshot snap = builder.build();

        assertThat(snap.rulesetText())
                .contains("tcp dport 80")
                .contains("tcp dport 443");
    }

    @Test
    @Transactional
    void ruleBuilder_directSiteGrant_producesRuleForSiteCidr() {
        Site grantingSite = persistSite("BranchOffice", "10.60.0.0/16");
        Site resourceSite = persistSite("HQ-Site", "10.61.0.0/16");
        Resource res = persistResource(resourceSite.id, "FileShare", "10.61.0.7");
        ResourcePort port = persistPort(res.id, 445, "tcp", "SMB");
        de.chriscohnen.islandr.acl.SiteResourceGrant grant =
                de.chriscohnen.islandr.acl.SiteResourceGrant.createNew(grantingSite.id, res.id, false);
        grant.persist();
        em.createNativeQuery("INSERT INTO site_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, grant.id).setParameter(2, port.id).executeUpdate();

        String text = builder.build().rulesetText();

        assertThat(text)
                .contains("ip saddr 10.60.0.0/16")
                .contains("ip daddr 10.61.0.7")
                .contains("tcp dport 445")
                .contains("resource=FileShare");
    }

    @Test
    @Transactional
    void ruleBuilder_directSiteGrant_coversPeerInsideCidrWithNoOwnGrant() {
        // The actual payoff of the feature: a peer with zero roles and zero
        // direct grants of its own still gets covered, purely because its IP
        // falls inside the granted site's CIDR — proven here by asserting the
        // CIDR-wide accept rule exists while the peer's own /32 never appears
        // anywhere in the ruleset (it produces no rule of its own at all).
        Site grantingSite = persistSite("BranchOffice2", "10.62.0.0/16");
        Site resourceSite = persistSite("HQ-Site2", "10.63.0.0/16");
        Resource res = persistResource(resourceSite.id, "Printer", "10.63.0.9");
        persistPort(res.id, 631, "tcp", "IPP");
        de.chriscohnen.islandr.acl.SiteResourceGrant.createNew(grantingSite.id, res.id, true).persist();
        // userId=null and no role/direct grant of its own — the per-Peer loop
        // skips this peer entirely (empty roles + empty direct grants).
        persistPeer(null, "branch-laptop", "10.62.0.42");

        String text = builder.build().rulesetText();

        assertThat(text)
                .contains("ip saddr 10.62.0.0/16")
                .doesNotContain("ip saddr 10.62.0.42");
    }

    @Test
    @Transactional
    void ruleBuilder_directSiteGrant_allPorts_emitsOneRulePerResourcePort() {
        Site grantingSite = persistSite("BranchOffice3", "10.64.0.0/16");
        Site resourceSite = persistSite("HQ-Site3", "10.65.0.0/16");
        Resource res = persistResource(resourceSite.id, "Multi", "10.65.0.9");
        persistPort(res.id, 80, "tcp", "HTTP");
        persistPort(res.id, 443, "tcp", "HTTPS");
        de.chriscohnen.islandr.acl.SiteResourceGrant.createNew(grantingSite.id, res.id, true).persist();

        RuleBuilder.Snapshot snap = builder.build();

        assertThat(snap.rulesetText())
                .contains("tcp dport 80")
                .contains("tcp dport 443");
    }

    @Test
    @Transactional
    void ruleBuilder_directSiteGrant_emitsIcmpForWholeSiteCidr() {
        // Locked-in decision: ICMP is not narrowed to a single peer for a
        // site-CIDR grant — it applies subnet-wide, same as the resource
        // access rule it rides alongside.
        Site grantingSite = persistSite("BranchOffice4", "10.66.0.0/16");
        Site resourceSite = persistSite("HQ-Site4", "10.67.0.0/16");
        Resource res = persistResource(resourceSite.id, "Terminal", "10.67.0.5");
        persistPort(res.id, 3389, "tcp", "RDP");
        de.chriscohnen.islandr.acl.SiteResourceGrant.createNew(grantingSite.id, res.id, true).persist();

        String text = builder.build().rulesetText();

        assertThat(text)
                .contains("ip saddr 10.66.0.0/16")
                .contains("icmp type echo-request");
    }

    @Test
    @Transactional
    void ruleBuilder_networkGrant_producesOneRulePerPeerWithSiteCidr() {
        User user = persistUser("nadia@example.test", "Nadia");
        Role role = persistRole("NetworkAdmins");
        addUserToRole(user.id, role.id);
        Site grantedSite = persistSite("BranchNet", "10.70.0.0/16");
        RoleNetworkGrant.createNew(role.id, grantedSite.id).persist();
        persistPeer(user.id, "nadia-laptop", "10.8.0.70");

        String text = builder.build().rulesetText();

        assertThat(text)
                .contains("ip saddr 10.8.0.70")
                .contains("ip daddr 10.70.0.0/16")
                .contains("accept");
        // No port/protocol clause at all — full-reach, not TCP/UDP-specific.
        assertThat(text).doesNotContain("dport");
    }

    @Test
    @Transactional
    void ruleBuilder_networkGrant_userWithoutRole_getsNoRule() {
        Role role = persistRole("NetworkAdmins2");
        Site grantedSite = persistSite("BranchNet2", "10.71.0.0/16");
        RoleNetworkGrant.createNew(role.id, grantedSite.id).persist();
        User unrelated = persistUser("otto@example.test", "Otto");
        persistPeer(unrelated.id, "otto-laptop", "10.8.0.71");

        String text = builder.build().rulesetText();

        assertThat(text).doesNotContain("10.71.0.0/16");
    }

    @Test
    @Transactional
    void ruleBuilder_networkGrant_autoAllRole_reachesEveryUserLinkedPeer() {
        Role everyone = persistRole("EveryoneNet");
        everyone.autoAll = true;
        everyone.persist();
        Site grantedSite = persistSite("BranchNet3", "10.72.0.0/16");
        RoleNetworkGrant.createNew(everyone.id, grantedSite.id).persist();
        User user = persistUser("paula@example.test", "Paula");
        // No explicit user_roles row — membership comes only from autoAll.
        persistPeer(user.id, "paula-phone", "10.8.0.72");

        String text = builder.build().rulesetText();

        assertThat(text)
                .contains("ip saddr 10.8.0.72")
                .contains("ip daddr 10.72.0.0/16");
    }

    @Test
    @Transactional
    void ruleBuilder_networkGrant_coexistsWithConcreteResourceGrant() {
        User user = persistUser("quentin@example.test", "Quentin");
        Role role = persistRole("NetworkAdmins3");
        addUserToRole(user.id, role.id);
        Site site = persistSite("BranchNet4", "10.73.0.0/16");
        Resource res = persistResource(site.id, "Printer4", "10.73.0.9");
        ResourcePort port = persistPort(res.id, 631, "tcp", "IPP");
        RoleResourceGrant concreteGrant = RoleResourceGrant.createNew(role.id, res.id, false);
        concreteGrant.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, concreteGrant.id).setParameter(2, port.id).executeUpdate();
        RoleNetworkGrant.createNew(role.id, site.id).persist();
        persistPeer(user.id, "quentin-laptop", "10.8.0.73");

        String text = builder.build().rulesetText();

        // Both the concrete-resource rule and the whole-network rule appear —
        // the two grant kinds don't collide or dedup against each other.
        assertThat(text)
                .contains("ip daddr 10.73.0.9")
                .contains("tcp dport 631")
                .contains("ip daddr 10.73.0.0/16");
    }

    // -- RulesetService ------------------------------------------------------

    @Test
    void rulesetService_apply_setsStatusOk_andRecordsRuleset() {
        rulesets.recomputeAndApply("test:admin");
        FirewallState s = readState();
        assertThat(s.lastStatus).isEqualTo(FirewallState.OK);
        assertThat(s.lastOkAt).isNotNull();
        assertThat(s.rulesetText).contains("table inet islandr");
        assertThat(s.stderrText).isNull();
    }

    @Test
    void rulesetService_apply_validationFailure_setsStatusFailed() {
        // Force the mock to reject every ruleset for this one test.
        mock().forceFailure = "syntax error at line 7";
        try {
            rulesets.recomputeAndApply("test:admin");
        } finally {
            mock().forceFailure = null;  // never leak into other tests
        }
        FirewallState s = readState();
        assertThat(s.lastStatus).isEqualTo(FirewallState.FAILED);
        assertThat(s.stderrText).isEqualTo("syntax error at line 7");
    }

    // -- FirewallResource ----------------------------------------------------

    @Test
    void firewallResource_get_returnsCurrentState() {
        rulesets.recomputeAndApply("test:admin");
        JsonPath body = given().when().get("/api/v1/firewall")
                .then().statusCode(200).extract().jsonPath();
        assertThat(body.getString("status")).isEqualTo("ok");
        assertThat(body.getString("rulesetText")).contains("table inet islandr");
    }

    @Test
    void firewallResource_resync_appliesAgain_andBumpsLastOkAt() {
        // First apply.
        given().when().post("/api/v1/firewall/resync").then().statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("ok"));
        // Second apply — status stays ok; the mock counts two distinct apply calls.
        given().when().post("/api/v1/firewall/resync").then().statusCode(200);
        assertThat(mock().applyCount).isGreaterThanOrEqualTo(2);
    }

    // -- Hook integration ---------------------------------------------------

    @Test
    void peerCreate_triggersFirewallApply() {
        int before = mock().applyCount;
        // Need a user first; user.create itself doesn't recompute (no grants),
        // peer.create does.
        String uid = given().contentType("application/json")
                .body("{\"name\":\"Felix\",\"email\":\"felix-" + UUID.randomUUID() + "@firma.de\"}")
                .when().post("/api/v1/users")
                .then().statusCode(201).extract().path("id");
        given().contentType("application/json")
                .body("{\"name\":\"laptop\",\"assignedIp\":\"10.8.0.20\"}")
                .when().post("/api/v1/users/" + uid + "/peers")
                .then().statusCode(201);
        // At least one extra apply since the start of the test.
        assertThat(mock().applyCount).isGreaterThan(before);
    }

    @Test
    void resync_includesAuditEntry() {
        long auditBefore = AuditLog.count();
        given().when().post("/api/v1/firewall/resync").then().statusCode(200);
        // recomputeAndApply writes one firewall.apply_ok row.
        assertThat(AuditLog.count()).isGreaterThan(auditBefore);
        AuditLog newest = AuditLog.<AuditLog>find("order by createdAt desc").firstResult();
        assertThat(newest.action).isEqualTo("firewall.apply_ok");
    }

    @Test
    @Transactional
    void ruleBuilder_portRange_emitsRangeRule() {
        User u = persistUser("range@example.test", "Rangeuser");
        Role role = persistRole("Dev");
        addUserToRole(u.id, role.id);
        Site site = persistSite("Lab", "10.30.0.0/16");
        Resource res = persistResource(site.id, "AppServer", "10.30.0.1");
        persistPort(res.id, 8080, 8090, "tcp", "HTTP");
        RoleResourceGrant grant = RoleResourceGrant.createNew(role.id, res.id, true);
        grant.persist();
        persistPeer(u.id, "dev-laptop", "10.8.0.10");

        RuleBuilder.Snapshot snap = builder.build();

        assertThat(snap.ruleCount()).isEqualTo(2); // 1 port rule + 1 ICMP
        assertThat(snap.rulesetText()).contains("tcp dport 8080-8090");
    }

    @Test
    @Transactional
    void ruleBuilder_allPortsSentinel_omitsDportClause() {
        User u = persistUser("allports@example.test", "Alluser");
        Role role = persistRole("Ops");
        addUserToRole(u.id, role.id);
        Site site = persistSite("Prod", "10.31.0.0/16");
        Resource res = persistResource(site.id, "Gateway", "10.31.0.1");
        persistPort(res.id, 0, null, "tcp", "CUSTOM");
        RoleResourceGrant grant = RoleResourceGrant.createNew(role.id, res.id, true);
        grant.persist();
        persistPeer(u.id, "ops-box", "10.8.0.11");

        RuleBuilder.Snapshot snap = builder.build();

        assertThat(snap.ruleCount()).isEqualTo(2); // 1 all-port rule + 1 ICMP
        assertThat(snap.rulesetText()).contains("tcp accept");
        assertThat(snap.rulesetText()).doesNotContain("dport 0");
    }

    @Test
    @Transactional
    void ruleBuilder_bothTransport_emitsTwoRules() {
        User u = persistUser("both@example.test", "Bothuser");
        Role role = persistRole("Svc");
        addUserToRole(u.id, role.id);
        Site site = persistSite("DMZ", "10.32.0.0/16");
        Resource res = persistResource(site.id, "DNS", "10.32.0.53");
        persistPort(res.id, 53, null, "both", "CUSTOM");
        RoleResourceGrant grant = RoleResourceGrant.createNew(role.id, res.id, true);
        grant.persist();
        persistPeer(u.id, "client", "10.8.0.12");

        RuleBuilder.Snapshot snap = builder.build();

        // 2 port rules (tcp dport 53 + udp dport 53) + 1 implicit ICMP
        assertThat(snap.ruleCount()).isEqualTo(3);
        assertThat(snap.rulesetText()).contains("tcp dport 53");
        assertThat(snap.rulesetText()).contains("udp dport 53");
    }

    @Test
    @Transactional
    void ruleBuilder_ipv6PeerAndResource_emitsIp6Rules() {
        User u = persistUser("v6user@example.test", "V6User");
        Role role = persistRole("V6Role");
        addUserToRole(u.id, role.id);
        Site site = persistSite("V6Site", "fd20::/64");
        Resource res = persistResource(site.id, "V6Server", "fd20::10");
        ResourcePort port = persistPort(res.id, 22, "tcp", "SSH");
        RoleResourceGrant grant = RoleResourceGrant.createNew(role.id, res.id, false);
        grant.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, grant.id).setParameter(2, port.id).executeUpdate();
        Peer peer = persistPeer(u.id, "v6-laptop", "10.8.0.99");
        peer.assignedIpv6 = "fd11::2";

        String text = builder.build().rulesetText();

        // IPv6 rule for the v6 peer IP → v6 resource IP
        assertThat(text).contains("ip6 saddr fd11::2");
        assertThat(text).contains("ip6 daddr fd20::10");
        assertThat(text).contains("icmpv6 type echo-request");
        // No IPv4 rule for this same (peer, resource) combination — different families
        assertThat(text).doesNotContain("ip saddr 10.8.0.99 ip daddr fd20::10");
    }

    @Test
    @Transactional
    void ruleBuilder_crossFamilyPairProducesNoRule() {
        User u = persistUser("cross@example.test", "CrossUser");
        Role role = persistRole("CrossRole");
        addUserToRole(u.id, role.id);
        // IPv4 resource, but peer only has IPv6 address for the rule we're testing
        Site site = persistSite("CrossSite", "10.50.0.0/16");
        Resource res = persistResource(site.id, "IPv4Res", "10.50.0.1");
        ResourcePort port = persistPort(res.id, 80, "tcp", "HTTP");
        RoleResourceGrant grant = RoleResourceGrant.createNew(role.id, res.id, false);
        grant.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, grant.id).setParameter(2, port.id).executeUpdate();
        // Peer with IPv6 only (assignedIp is required by DB, but we set it to a non-matching subnet)
        Peer peer = persistPeer(u.id, "v6only", "10.8.0.60");
        // Disconnect from IPv4 subnet for test purposes: only IPv6 address is relevant here
        peer.assignedIpv6 = "fd11::60";
        // IPv4 rule exists for the assignedIp vs IPv4 resource
        String text = builder.build().rulesetText();
        // IPv4 rule should exist (assignedIp 10.8.0.60 → 10.50.0.1)
        assertThat(text).contains("ip saddr 10.8.0.60");
        // IPv6 rule for fd11::60 → 10.50.0.1 must NOT be emitted (cross-family)
        assertThat(text).doesNotContain("ip6 saddr fd11::60 ip6 daddr 10.50.0.1");
    }

    // -- auto_all "Everyone" role (ADR-0013) ---------------------------------

    @Test
    @Transactional
    void ruleBuilder_autoAllRole_reachesUserWithNoExplicitRoles() {
        // User has a peer but NO user_roles row.
        User user = persistUser("nobody@example.test", "Nobody");
        Role everyone = Role.createNew("Everyone", null);
        everyone.autoAll = true;
        everyone.persist();
        Site site = persistSite("HQ", "10.20.0.0/16");
        Resource res = persistResource(site.id, "Terminal-01", "10.20.0.5");
        ResourcePort port = persistPort(res.id, 3389, "tcp", "RDP");
        RoleResourceGrant grant = RoleResourceGrant.createNew(everyone.id, res.id, false);
        grant.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, grant.id).setParameter(2, port.id).executeUpdate();
        persistPeer(user.id, "laptop", "10.8.0.9");

        String text = builder.build().rulesetText();
        // Reaches the Everyone-granted resource despite no explicit role membership.
        assertThat(text).contains("ip saddr 10.8.0.9 ip daddr 10.20.0.5 tcp dport 3389 accept");
    }

    @Test
    @Transactional
    void ruleBuilder_autoAllRole_doesNotReachSitePeers() {
        // A site/gateway peer has userId=null — Everyone models user access, not routing.
        Role everyone = Role.createNew("Everyone", null);
        everyone.autoAll = true;
        everyone.persist();
        Site site = persistSite("HQ", "10.20.0.0/16");
        Resource res = persistResource(site.id, "Terminal-01", "10.20.0.5");
        ResourcePort port = persistPort(res.id, 3389, "tcp", "RDP");
        RoleResourceGrant grant = RoleResourceGrant.createNew(everyone.id, res.id, false);
        grant.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, grant.id).setParameter(2, port.id).executeUpdate();
        persistPeer(null, "site-gw", "10.8.0.99");

        String text = builder.build().rulesetText();
        assertThat(text).doesNotContain("ip saddr 10.8.0.99");
    }

    // -- helpers --------------------------------------------------------------

    @Transactional
    User persistUser(String email, String name) {
        User u = User.createNew(name, email);
        u.persist();
        return u;
    }

    @Transactional
    Role persistRole(String name) {
        Role r = Role.createNew(name, null);
        r.persist();
        return r;
    }

    @Transactional
    void addUserToRole(String userId, String roleId) {
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, userId).setParameter(2, roleId).executeUpdate();
    }

    @Transactional
    Site persistSite(String name, String cidr) {
        Site s = Site.createNew(name, cidr, null);
        s.persist();
        return s;
    }

    @Transactional
    Resource persistResource(String siteId, String name, String ip) {
        Resource r = Resource.createNew(siteId, name, ip, null, "computer");
        r.persist();
        return r;
    }

    @Transactional
    ResourcePort persistPort(String resourceId, int port, String transport, String protocol) {
        return persistPort(resourceId, port, null, transport, protocol);
    }

    @Transactional
    ResourcePort persistPort(String resourceId, int port, Integer portEnd, String transport, String protocol) {
        ResourcePort p = ResourcePort.createNew(resourceId, port, portEnd, transport, protocol, null, null, true, false, "native");
        p.persist();
        return p;
    }

    @Transactional
    Peer persistPeer(String userId, String name, String assignedIp) {
        // public_key has a unique index (V2__create_peers.sql) — generate a
        // distinct WireGuard-shaped key per call so tests needing more than
        // one peer per user (e.g. direct-user-grant "every peer" coverage)
        // don't collide on a shared hardcoded key.
        byte[] randomKeyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(randomKeyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(randomKeyBytes);
        Peer p = Peer.createNew(userId, name, publicKey, assignedIp);
        p.persist();
        return p;
    }

    @Transactional
    FirewallState readState() {
        // Re-fetch so the test reads a fresh snapshot of the row, not the
        // managed instance from the apply() call which the JTA TX commits
        // before this @Test method returns.
        FirewallState.getEntityManager().clear();
        return FirewallState.findById(FirewallState.SINGLETON_ID);
    }
}
