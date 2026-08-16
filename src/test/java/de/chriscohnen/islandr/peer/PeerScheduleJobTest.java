package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.ResourcePort;
import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleResourceGrant;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.firewall.RuleBuilder;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link PeerScheduleJob#tick()} directly, bypassing the config-flag
 * gate and the 60s timer (mirrors ProxyReconcilerTest's pattern) — the %test
 * profile sets {@code islandr.peer-schedule.enabled=false} precisely so the
 * background timer itself never fires during a test run.
 *
 * <p>Schedules use {@code weekdayMask=0} for "definitely closed regardless of
 * the current wall-clock time" and {@code weekdayMask=127} with a near-24h
 * same-day window for "definitely open" — this sidesteps needing a Clock
 * abstraction in the job just to make window boundaries deterministic in tests.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PeerScheduleJobTest {

    @Inject PeerScheduleJob job;
    @Inject RuleBuilder builder;
    @PersistenceContext EntityManager em;

    private static final int ALL_DAYS = 127;

    @BeforeEach
    @Transactional
    void wipe() { wipeAll(); }

    @AfterEach
    @Transactional
    void cleanup() { wipeAll(); }

    private void wipeAll() {
        PeerSchedule.deleteAll();
        RoleResourceGrant.deleteAll();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        Peer.deleteAll();
        AuditLog.deleteAll();
    }

    @Transactional
    Peer persistPeer(boolean enabled, String enabledSource, Instant validUntil) {
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        Peer p = Peer.createNew(null, "sched-test-" + UUID.randomUUID(), publicKey,
                "10.8.0." + (100 + (int) (Math.random() * 100)));
        p.enabled = enabled;
        p.enabledSource = enabledSource;
        p.validUntil = validUntil;
        p.persist();
        return p;
    }

    @Transactional
    PeerSchedule persistSchedule(String peerId, int weekdayMask, LocalTime from, LocalTime to) {
        PeerSchedule s = PeerSchedule.createNew(peerId, weekdayMask, from, to);
        s.persist();
        return s;
    }

    /** A same-day window covering virtually the entire day (00:00-23:59) — open
     *  at any wall-clock time the test happens to run, except the last minute. */
    private static LocalTime openFrom() { return LocalTime.MIDNIGHT; }
    private static LocalTime openTo() { return LocalTime.of(23, 59); }

    @Test
    void tick_disablesPeerWhenScheduleWindowClosed() {
        Peer p = persistPeer(true, null, null);
        persistSchedule(p.id, 0 /* no day ever matches */, LocalTime.of(8, 0), LocalTime.of(18, 0));

        job.tick();

        Peer reloaded = Peer.findById(p.id);
        assertThat(reloaded.enabled).isFalse();
        assertThat(reloaded.enabledSource).isEqualTo("schedule");
    }

    @Test
    void tick_enablesPeerWhenScheduleWindowOpen() {
        Peer p = persistPeer(false, "schedule", null);
        persistSchedule(p.id, ALL_DAYS, openFrom(), openTo());

        job.tick();

        Peer reloaded = Peer.findById(p.id);
        assertThat(reloaded.enabled).isTrue();
        assertThat(reloaded.enabledSource).isEqualTo("schedule");
    }

    @Test
    void tick_validUntilInPast_disablesPeerAndIsTerminalDespiteOpenWindow() {
        Peer p = persistPeer(true, null, Instant.now().minus(1, ChronoUnit.DAYS));
        // Even a wide-open recurring window must not resurrect an expired peer.
        persistSchedule(p.id, ALL_DAYS, openFrom(), openTo());

        job.tick();

        Peer reloaded = Peer.findById(p.id);
        assertThat(reloaded.enabled).isFalse();
    }

    @Test
    void tick_manualDisableDuringOpenWindow_isNotOverriddenAbsentAnEdge() {
        // Admin disabled this peer while its window happens to be open both
        // now and a tick ago — no open<->close edge occurred, so the manual
        // override must hold.
        Peer p = persistPeer(false, "manual", null);
        persistSchedule(p.id, ALL_DAYS, openFrom(), openTo());

        job.tick();

        Peer reloaded = Peer.findById(p.id);
        assertThat(reloaded.enabled).isFalse();
        assertThat(reloaded.enabledSource).isEqualTo("manual");
    }

    @Test
    @Transactional
    void tick_disablingPeer_removesItsFirewallRule() {
        User user = User.createNew("Sched Test User", "sched-test-" + UUID.randomUUID() + "@example.test");
        user.persist();
        Role role = Role.createNew("Sched Test Role", null);
        role.persist();
        Site site = Site.createNew("Sched-Test-Site", "10.70.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "Sched-Test-Res", "10.70.0.5", null, "computer");
        res.persist();
        ResourcePort port = ResourcePort.createNew(res.id, 22, null, "tcp", "SSH", null, null, true, false, "native");
        port.persist();
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
        RoleResourceGrant.createNew(role.id, res.id, true).persist();

        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        Peer peer = Peer.createNew(user.id, "sched-test-peer", publicKey, "10.8.0.201");
        peer.persist();

        String beforeText = builder.build().rulesetText();
        assertThat(beforeText).contains("10.8.0.201");

        PeerSchedule s = PeerSchedule.createNew(peer.id, 0, LocalTime.of(8, 0), LocalTime.of(18, 0));
        s.persist();
        job.tick();

        String afterText = builder.build().rulesetText();
        assertThat(afterText).doesNotContain("10.8.0.201");
    }
}
