package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Direct tests of {@link UserGrantExpiryJob#expireDue()} — the ad-hoc
 *  temporary grant's auto-revoke mechanism (issue #70). Package-private
 *  method, called directly rather than waiting on the real 60s
 *  {@code @Scheduled} cadence. */
@QuarkusTest
class UserGrantExpiryJobTest {

    @Inject UserGrantExpiryJob job;
    @Inject UserGrantService grants;
    @Inject AuditService audit;

    @PersistenceContext EntityManager em;

    @BeforeEach
    void wipe() { wipeAll(); }

    @AfterEach
    void cleanup() { wipeAll(); }

    @Transactional
    void wipeAll() {
        em.createNativeQuery("DELETE FROM user_resource_grant_ports").executeUpdate();
        UserResourceGrant.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        User.delete("email", "grant-expiry-test@example.test");
    }

    private record Seed(String userId, String resourceId) {}

    @Transactional
    Seed seed() {
        User user = User.createNew("Grant Expiry User", "grant-expiry-test@example.test");
        user.persist();
        Site site = Site.createNew("Homeoffice", "10.34.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "NAS", "10.34.0.5", null, "nas");
        res.persist();
        return new Seed(user.id, res.id);
    }

    @Transactional
    UserResourceGrant grantWithValidUntil(Seed s, Instant validUntil) {
        UserResourceGrant g = UserResourceGrant.createNew(s.userId(), s.resourceId(), true);
        g.validUntil = validUntil;
        g.persist();
        return g;
    }

    @Test
    void expireDue_removesGrantPastValidUntil() {
        Seed s = seed();
        UserResourceGrant g = grantWithValidUntil(s, Instant.now().minus(1, ChronoUnit.MINUTES));

        job.expireDue();

        assertThat(readGrant(g.id)).isNull();
    }

    @Test
    void expireDue_leavesFutureGrantAlone() {
        Seed s = seed();
        UserResourceGrant g = grantWithValidUntil(s, Instant.now().plus(1, ChronoUnit.HOURS));

        job.expireDue();

        assertThat(readGrant(g.id)).isNotNull();
    }

    @Test
    void expireDue_leavesPermanentGrantAlone() {
        Seed s = seed();
        UserResourceGrant g = grantWithValidUntil(s, null);

        job.expireDue();

        assertThat(readGrant(g.id)).isNotNull();
    }

    @Test
    void expireDue_writesDistinctAuditAction() {
        Seed s = seed();
        grantWithValidUntil(s, Instant.now().minus(1, ChronoUnit.MINUTES));

        job.expireDue();

        assertThat(recentAuditActions()).contains("user_grant.expire");
    }

    @Transactional
    UserResourceGrant readGrant(String id) {
        return UserResourceGrant.findById(id);
    }

    @Transactional
    java.util.List<String> recentAuditActions() {
        @SuppressWarnings("unchecked")
        java.util.List<String> actions = em.createNativeQuery(
                        "SELECT action FROM audit_log WHERE actor = 'system:user-grant-expiry' ORDER BY created_at DESC LIMIT 5")
                .getResultList();
        return actions;
    }
}
