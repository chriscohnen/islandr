package de.chriscohnen.islandr.peer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * CRUD + pure evaluation logic for {@link PeerSchedule} (issue #47). Kept
 * separate from {@link PeerService}, which owns WireGuard push/remove — a
 * different concern from schedule bookkeeping and window math.
 *
 * <p>Server-local time, not per-peer/per-admin timezone-aware — a deliberate
 * v1 scope limit (see the migration comment on V62 and the entity javadoc).
 */
@ApplicationScoped
public class PeerScheduleService {

    @Transactional
    public PeerScheduleDto.Response upsert(String peerId, PeerScheduleDto.Request req) {
        if (Peer.findById(peerId) == null) throw new NotFoundException("peer not found: " + peerId);
        PeerSchedule s = PeerSchedule.findByPeer(peerId);
        if (s == null) {
            s = PeerSchedule.createNew(peerId, req.weekdayMask(), req.activeFrom(), req.activeTo());
            s.persist();
        } else {
            s.weekdayMask = req.weekdayMask();
            s.activeFrom = req.activeFrom().toString();
            s.activeTo = req.activeTo().toString();
            s.updatedAt = Instant.now();
        }
        return PeerScheduleDto.Response.from(s);
    }

    @Transactional
    public void remove(String peerId) {
        PeerSchedule s = PeerSchedule.findByPeer(peerId);
        if (s != null) s.delete();
    }

    public PeerScheduleDto.Response find(String peerId) {
        PeerSchedule s = PeerSchedule.findByPeer(peerId);
        return s == null ? null : PeerScheduleDto.Response.from(s);
    }

    /** Every peer's schedule, for the peers list to show a schedule indicator
     *  without an N+1 fetch per row. */
    public List<PeerScheduleDto.Response> list() {
        return PeerSchedule.<PeerSchedule>listAll().stream().map(PeerScheduleDto.Response::from).toList();
    }

    /**
     * Whether the schedule says a peer should be enabled at {@code now}
     * (server-local time). {@code weekdayMask}'s bit for a given day means
     * "the window <em>starts</em> on that day" — for a same-day window that's
     * simply "today's bit is set and now falls in [from, to)". For an
     * overnight-spanning window ({@code activeFrom > activeTo}, e.g.
     * 22:00-06:00) the window that started <em>yesterday</em> evening is still
     * running during today's early morning, so the "before `to`" half must be
     * checked against yesterday's bit, not today's — otherwise a mask with
     * only, say, Wednesday set would (wrongly) also cover Wednesday's own
     * early morning instead of Thursday's (the actual continuation of the
     * window Wednesday started).
     */
    public boolean evaluateWindow(PeerSchedule schedule, Instant now) {
        ZonedDateTime local = now.atZone(ZoneId.systemDefault());
        DayOfWeek today = local.getDayOfWeek();
        DayOfWeek yesterday = today.minus(1);
        int todayBit = 1 << (today.getValue() - 1);         // Monday=1 -> bit0 ... Sunday=7 -> bit6
        int yesterdayBit = 1 << (yesterday.getValue() - 1);

        LocalTime t = local.toLocalTime();
        LocalTime from = schedule.activeFromTime();
        LocalTime to = schedule.activeToTime();
        if (from.equals(to)) return false; // zero-width window — never active

        if (from.isBefore(to)) {
            return (schedule.weekdayMask & todayBit) != 0 && !t.isBefore(from) && t.isBefore(to);
        }
        boolean eveningPortion = (schedule.weekdayMask & todayBit) != 0 && !t.isBefore(from);
        boolean earlyMorningPortion = (schedule.weekdayMask & yesterdayBit) != 0 && t.isBefore(to);
        return eveningPortion || earlyMorningPortion;
    }
}
