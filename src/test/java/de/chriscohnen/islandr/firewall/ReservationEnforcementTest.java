package de.chriscohnen.islandr.firewall;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.ResourcePort;
import de.chriscohnen.islandr.acl.ResourceReservation;
import de.chriscohnen.islandr.acl.ReservationService;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.acl.UserResourceGrant;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enforcement half of issue #72: a grant on a capacity-limited resource
 * must not produce an nftables rule unless the user holds a live reservation.
 *
 * <p>This is what makes the reservation genuinely mandatory rather than an
 * alternative route to access — {@link de.chriscohnen.islandr.acl.AclService}
 * agreeing in the abstract is not enough if the ruleset still opens the port.
 */
@QuarkusTest
class ReservationEnforcementTest {

    @Inject RuleBuilder builder;
    @Inject ReservationService reservations;
    @Inject EntityManager em;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    @Transactional
    void cleanup() {
        // Reservations carry an FK to resources; drop them before the rows
        // they point at, and before any later class wipes resources itself.
        em.createNativeQuery("DELETE FROM resource_reservations").executeUpdate();
        em.createNativeQuery("DELETE FROM user_resource_grant_ports").executeUpdate();
        UserResourceGrant.delete("resourceId in (select id from Resource where name like ?1)", "RSV-%" + suffix);
        Peer.delete("name like ?1", "rsv-%" + suffix);
        ResourcePort.delete("resourceId in (select id from Resource where name like ?1)", "RSV-%" + suffix);
        Resource.delete("name like ?1", "RSV-%" + suffix);
        Site.delete("name like ?1", "RSV-Site-" + suffix);
        User.delete("email like ?1", "rsv-%" + suffix + "@example.test");
    }

    record Fixture(String userId, String resourceId, String portId, String peerIp, String resourceIp) {}

    @Transactional
    Fixture buildFixture(Integer capacity) {
        Site site = Site.createNew("RSV-Site-" + suffix, "10.91.0.0/24", null);
        site.persist();

        Resource res = Resource.createNew(site.id, "RSV-Res-" + suffix, "10.91.0.10", null, "computer");
        res.persist();
        // The gated port. A second, ungated one lives alongside it in
        // sshStaysOpen... below — capacity is per port, not per host.
        ResourcePort port = ResourcePort.createNew(res.id, 3389, null, "tcp", "RDP", null,
                null, false, false, "native");
        port.maxConcurrentUsers = capacity;
        port.persist();

        User u = User.createNew("RSV User " + suffix, "rsv-u-" + suffix + "@example.test");
        u.persist();

        // A direct grant: eligibility, deliberately with no reservation yet.
        UserResourceGrant g = UserResourceGrant.createNew(u.id, res.id, true);
        g.persist();

        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        Peer p = Peer.createNew(u.id, "rsv-peer-" + suffix, Base64.getEncoder().encodeToString(key), "10.8.9.21");
        p.persist();

        return new Fixture(u.id, res.id, port.id, p.assignedIp, res.ip);
    }

    @Test
    @Transactional
    void grantWithoutReservation_onCapacityLimitedResource_emitsNoRule() {
        Fixture f = buildFixture(1);

        RuleBuilder.Snapshot snap = builder.build();

        assertThat(snap.rulesetText())
                .as("a standing grant alone must not open a capacity-limited resource — "
                        + "otherwise three role-granted users defeat a one-session host")
                .doesNotContain(f.resourceIp() + " tcp dport 3389");
    }

    @Test
    void grantWithALiveReservation_emitsTheRule() {
        Fixture f = buildFixture(1);
        reservations.request(f.userId(), f.portId(), 60);

        RuleBuilder.Snapshot snap = buildRuleset();

        assertThat(snap.rulesetText())
                .contains(f.peerIp())
                .contains("3389");
    }

    @Test
    void whenTheReservationExpires_theRuleDisappearsAgain() {
        Fixture f = buildFixture(1);
        ResourceReservation r = reservations.request(f.userId(), f.portId(), 60);
        assertThat(buildRuleset().rulesetText()).contains("3389");

        backdate(r.id);

        assertThat(buildRuleset().rulesetText())
                .as("a closed window must withdraw access on the next recompute")
                .doesNotContain(f.resourceIp() + " tcp dport 3389");
    }

    /**
     * The reason capacity is per port: taking the single RDP seat must not
     * close SSH on the same machine for everybody else.
     */
    @Test
    void anUngatedPortOnTheSameHostStaysOpenWhileTheGatedOneIsHeld() {
        Fixture f = buildFixture(1);
        addOpenSshPort(f.resourceId());

        RuleBuilder.Snapshot snap = buildRuleset();

        assertThat(snap.rulesetText())
                .as("SSH is not capacity-limited, so the grant alone still opens it")
                .contains(f.resourceIp() + " tcp dport 22");
        assertThat(snap.rulesetText())
                .as("RDP is gated and unheld")
                .doesNotContain(f.resourceIp() + " tcp dport 3389");
    }

    @Transactional
    void addOpenSshPort(String resourceId) {
        ResourcePort ssh = ResourcePort.createNew(resourceId, 22, null, "tcp", "SSH", null,
                null, false, false, "native");
        ssh.persist();
    }

    /**
     * The backward-compatibility guarantee: every port that existed before
     * #72 has a null capacity and must keep behaving exactly as it did.
     */
    @Test
    @Transactional
    void portWithoutACapacityLimit_stillEmitsOnTheGrantAlone() {
        Fixture f = buildFixture(null);

        RuleBuilder.Snapshot snap = builder.build();

        assertThat(snap.rulesetText())
                .contains(f.peerIp())
                .contains("3389");
    }

    @Transactional
    RuleBuilder.Snapshot buildRuleset() {
        return builder.build();
    }

    @Transactional
    void backdate(String reservationId) {
        ResourceReservation r = ResourceReservation.findById(reservationId);
        r.startsAt = Instant.now().minusSeconds(7200);
        r.endsAt = Instant.now().minusSeconds(60);
    }
}
