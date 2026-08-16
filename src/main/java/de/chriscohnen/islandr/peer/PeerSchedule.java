package de.chriscohnen.islandr.peer;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A recurring weekly enable window for one peer (issue #47) — at most one row
 * per peer. {@code weekdayMask} bit0=Monday...bit6=Sunday. {@code activeFrom}/
 * {@code activeTo} are stored as "HH:mm" strings (see V62 migration comment
 * for why, not a native TIME column); {@code activeFrom > activeTo} means an
 * overnight-spanning window. Server-local time, not per-peer timezone-aware —
 * a deliberate v1 scope limit, see PeerScheduleService.evaluateWindow.
 */
@Entity
@Table(name = "peer_schedules")
public class PeerSchedule extends PanacheEntityBase {
    @Id @Column(name = "id", nullable = false, length = 36)
    public String id;
    @Column(name = "peer_id", nullable = false, length = 36)
    public String peerId;
    @Column(name = "weekday_mask", nullable = false)
    public int weekdayMask;
    @Column(name = "active_from", nullable = false, length = 5)
    public String activeFrom;
    @Column(name = "active_to", nullable = false, length = 5)
    public String activeTo;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public LocalTime activeFromTime() { return LocalTime.parse(activeFrom); }
    public LocalTime activeToTime() { return LocalTime.parse(activeTo); }

    public static PeerSchedule createNew(String peerId, int weekdayMask, LocalTime activeFrom, LocalTime activeTo) {
        PeerSchedule s = new PeerSchedule();
        s.id = UUID.randomUUID().toString();
        s.peerId = peerId;
        s.weekdayMask = weekdayMask;
        s.activeFrom = activeFrom.toString();
        s.activeTo = activeTo.toString();
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        return s;
    }

    public static PeerSchedule findByPeer(String peerId) {
        return find("peerId", peerId).firstResult();
    }
}
