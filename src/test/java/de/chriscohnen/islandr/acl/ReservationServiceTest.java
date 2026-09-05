package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exclusive-capacity reservations (issue #72). Covers the two rules the issue
 * is most specific about: a request at capacity is rejected outright and told
 * who is holding the resource, and a standing grant on a capacity-limited
 * resource does not on its own confer access.
 */
@QuarkusTest
class ReservationServiceTest {

    @Inject ReservationService reservations;
    @Inject AclService acl;

    /**
     * Builds a site + resource + two ports + two users with a direct grant, so
     * both are eligible but hold nothing. The second port is deliberately left
     * unlimited: it is what proves capacity is scoped to a port rather than
     * locking the whole host.
     */
    @Transactional
    Fixture fixture(Integer capacity, Integer maxMinutes, boolean autoApprove) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Site site = Site.createNew("Site " + suffix, "10.90.0.0/24", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "Res " + suffix, "10.90.0." + (2 + counter()), null, "computer");
        res.persist();

        ResourcePort gated = ResourcePort.createNew(res.id, 3389, null, "tcp", "RDP", null,
                null, false, false, "native");
        gated.maxConcurrentUsers = capacity;
        gated.maxReservationMinutes = maxMinutes;
        gated.autoApproveReservations = autoApprove;
        gated.persist();

        ResourcePort open = ResourcePort.createNew(res.id, 22, null, "tcp", "SSH", null,
                null, false, false, "native");
        open.persist();

        User u = newUser("u1-" + suffix);
        User other = newUser("u2-" + suffix);
        grant(u.id, res.id);
        grant(other.id, res.id);
        return new Fixture(res.id, gated.id, open.id, u.id, other.id);
    }

    record Fixture(String resourceId, String portId, String openPortId,
                   String userId, String otherUserId) {}

    private static int seq = 10;
    private static synchronized int counter() { return seq++ % 200; }

    @Transactional
    User newUser(String name) {
        User u = User.createNew(name, name + "@example.com");
        u.persist();
        return u;
    }

    @Transactional
    void grant(String userId, String resourceId) {
        UserResourceGrant g = UserResourceGrant.createNew(userId, resourceId, true);
        g.persist();
    }

    @Test
    void request_onIdleAutoApproveResource_isImmediatelyActive() {
        Fixture f = fixture(1, null, true);
        ResourceReservation r = reservations.request(f.userId(), f.portId(), 60);
        assertThat(r.status).isEqualTo(ResourceReservation.ACTIVE);
        assertThat(r.endsAt).isAfter(Instant.now());
        assertThat(r.decidedBy).as("auto-approved rows have no decider — nobody clicked approve").isNull();
    }

    @Test
    void request_whenAutoApproveIsOff_staysPendingEvenThoughResourceIsIdle() {
        Fixture f = fixture(1, null, false);
        ResourceReservation r = reservations.request(f.userId(), f.portId(), 60);
        assertThat(r.status).isEqualTo(ResourceReservation.PENDING);
        assertThat(r.startsAt).isNull();
        assertThat(r.endsAt).isNull();
    }

    /**
     * The headline rule of #72: reject-outright, and name the holder so the
     * requester can go and coordinate rather than being told a bare "no".
     */
    @Test
    void request_atCapacity_isRejectedOutright_andNamesTheCurrentHolder() {
        Fixture f = fixture(1, null, true);
        reservations.request(f.userId(), f.portId(), 60);

        assertThatThrownBy(() -> reservations.request(f.otherUserId(), f.portId(), 60))
                .isInstanceOf(ReservationService.AtCapacityException.class)
                .satisfies(ex -> {
                    List<ReservationService.Holder> holders =
                            ((ReservationService.AtCapacityException) ex).holders;
                    assertThat(holders).hasSize(1);
                    assertThat(holders.get(0).userId()).isEqualTo(f.userId());
                    assertThat(holders.get(0).until()).isNotNull();
                });
    }

    @Test
    void request_atCapacity_doesNotQueue_noPendingRowIsLeftBehind() {
        Fixture f = fixture(1, null, true);
        reservations.request(f.userId(), f.portId(), 60);
        try {
            reservations.request(f.otherUserId(), f.portId(), 60);
        } catch (ReservationService.AtCapacityException expected) { /* the point of the test */ }

        assertThat(ResourceReservation.<ResourceReservation>list("userId", f.otherUserId()))
                .as("a refused request must leave nothing behind — no waitlist")
                .isEmpty();
    }

    @Test
    void request_capacityTwo_admitsASecondUser() {
        Fixture f = fixture(2, null, true);
        reservations.request(f.userId(), f.portId(), 60);
        ResourceReservation second = reservations.request(f.otherUserId(), f.portId(), 60);
        assertThat(second.status).isEqualTo(ResourceReservation.ACTIVE);
    }

    @Test
    void request_onPortWithoutCapacityLimit_isRefused() {
        Fixture f = fixture(null, null, true);
        assertThatThrownBy(() -> reservations.request(f.userId(), f.portId(), 60))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * The reason capacity moved from the resource to the port: one seat taken
     * on RDP must not lock everyone else off the machine's SSH port.
     */
    @Test
    void takingTheGatedPort_leavesTheHostsOtherPortsAlone() {
        Fixture f = fixture(1, null, true);
        reservations.request(f.userId(), f.portId(), 60);

        ResourcePort open = ResourcePort.findById(f.openPortId());
        assertThat(acl.canReachPortNow(f.otherUserId(), open))
                .as("the ungated SSH port stays reachable while RDP is held by someone else")
                .isTrue();
        ResourcePort gated = ResourcePort.findById(f.portId());
        assertThat(acl.canReachPortNow(f.otherUserId(), gated)).isFalse();
    }

    @Test
    void request_withoutAGrant_isForbidden_beforeAnyCapacityInformationLeaks() {
        Fixture f = fixture(1, null, true);
        User stranger = newUser("stranger-" + UUID.randomUUID().toString().substring(0, 8));
        assertThatThrownBy(() -> reservations.request(stranger.id, f.portId(), 60))
                .as("an ineligible user must not learn who holds a resource by probing it")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void request_twiceBySamePort_isRefused() {
        Fixture f = fixture(2, null, true);
        reservations.request(f.userId(), f.portId(), 60);
        assertThatThrownBy(() -> reservations.request(f.userId(), f.portId(), 60))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void request_longerThanTheResourceCeiling_isTrimmedNotRejected() {
        Fixture f = fixture(1, 240, true);       // 4h ceiling
        ResourceReservation r = reservations.request(f.userId(), f.portId(), 1440);  // asks for 24h
        assertThat(r.requestedMinutes).isEqualTo(240);
        assertThat(r.endsAt).isBefore(Instant.now().plusSeconds(241 * 60L));
    }

    @Test
    void request_withAnUnsupportedDuration_isRefused() {
        Fixture f = fixture(1, null, true);
        assertThatThrownBy(() -> reservations.request(f.userId(), f.portId(), 37))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void cancelOwn_freesTheSlotForSomeoneElse() {
        Fixture f = fixture(1, null, true);
        ResourceReservation mine = reservations.request(f.userId(), f.portId(), 60);
        reservations.cancelOwn(f.userId(), mine.id);

        ResourceReservation next = reservations.request(f.otherUserId(), f.portId(), 60);
        assertThat(next.status).isEqualTo(ResourceReservation.ACTIVE);
    }

    @Test
    void cancelOwn_someoneElsesReservation_isForbidden() {
        Fixture f = fixture(1, null, true);
        ResourceReservation mine = reservations.request(f.userId(), f.portId(), 60);
        assertThatThrownBy(() -> reservations.cancelOwn(f.otherUserId(), mine.id))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void approve_aPendingRequest_activatesIt() {
        Fixture f = fixture(1, null, false);
        ResourceReservation r = reservations.request(f.userId(), f.portId(), 60);
        ResourceReservation approved = reservations.approve(r.id, "admin");
        assertThat(approved.status).isEqualTo(ResourceReservation.ACTIVE);
        assertThat(approved.decidedBy).isEqualTo("admin");
        assertThat(approved.endsAt).isAfter(Instant.now());
    }

    /**
     * The capacity is re-checked at decision time, not trusted from when the
     * request was filed — an admin works a queue that may have filled up since.
     */
    @Test
    void approve_whenCapacityFilledUpSinceTheRequest_isRefused() {
        Fixture f = fixture(1, null, false);
        ResourceReservation pending = reservations.request(f.userId(), f.portId(), 60);
        ResourceReservation other = reservations.request(f.otherUserId(), f.portId(), 60);
        reservations.approve(other.id, "admin");     // takes the only slot

        assertThatThrownBy(() -> reservations.approve(pending.id, "admin"))
                .isInstanceOf(ReservationService.AtCapacityException.class);
    }

    @Test
    void reject_leavesTheSlotFree() {
        Fixture f = fixture(1, null, false);
        ResourceReservation r = reservations.request(f.userId(), f.portId(), 60);
        ResourceReservation rejected = reservations.reject(r.id, "admin");
        assertThat(rejected.status).isEqualTo(ResourceReservation.REJECTED);
        assertThat(reservations.liveReservations(f.portId(), Instant.now())).isEmpty();
    }

    /**
     * Point 5 of the issue: a standing grant establishes eligibility, the
     * reservation establishes the slot. Without this, an admin handing three
     * people a role grant would defeat a one-session resource's capacity limit.
     */
    @Test
    void canReachPortNow_withGrantButNoReservation_onCapacityLimitedPort_isFalse() {
        Fixture f = fixture(1, null, true);
        assertThat(acl.hasAnyGrant(f.userId(), f.resourceId()))
                .as("the grant itself is untouched — eligibility is unchanged").isTrue();
        assertThat(acl.canReachPortNow(f.userId(), ResourcePort.findById(f.portId())))
                .as("but eligibility alone must not reach a capacity-limited port").isFalse();
    }

    @Test
    void canReachPortNow_withAnActiveReservation_isTrue() {
        Fixture f = fixture(1, null, true);
        reservations.request(f.userId(), f.portId(), 60);
        assertThat(acl.canReachPortNow(f.userId(), ResourcePort.findById(f.portId()))).isTrue();
    }

    @Test
    void canReachPortNow_onAnUnlimitedPort_isGrantOnly_unchangedFromBeforeIssue72() {
        Fixture f = fixture(null, null, true);
        assertThat(acl.canReachPortNow(f.userId(), ResourcePort.findById(f.portId())))
                .as("ports without a capacity limit must behave exactly as before")
                .isTrue();
    }

    /**
     * The DNS resolver asks this resource-level question. A host keeps
     * resolving while any of its ports is reachable — only one whose every
     * port is gated and unheld disappears.
     */
    @Test
    void canReachNow_staysTrueWhileAnUngatedPortRemains() {
        Fixture f = fixture(1, null, true);
        reservations.request(f.userId(), f.portId(), 60);
        assertThat(acl.canReachNow(f.otherUserId(), f.resourceId()))
                .as("SSH is still open, so the name must still resolve")
                .isTrue();
    }

    @Test
    void dueForExpiry_findsOnlyReservationsWhoseWindowHasClosed() {
        Fixture f = fixture(2, null, true);
        ResourceReservation live = reservations.request(f.userId(), f.portId(), 60);
        ResourceReservation stale = reservations.request(f.otherUserId(), f.portId(), 60);
        backdate(stale.id);

        List<String> dueIds = reservations.dueForExpiry(Instant.now()).stream().map(r -> r.id).toList();
        assertThat(dueIds).contains(stale.id).doesNotContain(live.id);
    }

    @Test
    void anExpiredReservationStopsConferringAccess_andFreesTheSlot() {
        Fixture f = fixture(1, null, true);
        ResourceReservation mine = reservations.request(f.userId(), f.portId(), 60);
        backdate(mine.id);

        assertThat(acl.canReachPortNow(f.userId(), ResourcePort.findById(f.portId())))
                .as("a window that has closed must not still reach the port").isFalse();
        // And the slot is genuinely free again, not merely uncounted.
        ResourceReservation next = reservations.request(f.otherUserId(), f.portId(), 60);
        assertThat(next.status).isEqualTo(ResourceReservation.ACTIVE);
    }

    /** Pushes a reservation's window into the past without waiting an hour for it. */
    @Transactional
    void backdate(String reservationId) {
        ResourceReservation r = ResourceReservation.findById(reservationId);
        r.startsAt = Instant.now().minusSeconds(7200);
        r.endsAt = Instant.now().minusSeconds(60);
    }
}
