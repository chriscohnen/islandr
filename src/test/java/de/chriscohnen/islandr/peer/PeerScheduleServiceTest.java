package de.chriscohnen.islandr.peer;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure evaluateWindow logic — no CDI/DB needed. Instants are built against
 *  the JVM's default zone (same as evaluateWindow itself) so the test is
 *  deterministic regardless of which timezone CI runs in. */
class PeerScheduleServiceTest {

    private final PeerScheduleService svc = new PeerScheduleService();

    /** A fixed reference Monday, moved forward to the given weekday within the
     *  same week and set to the given local time, in the system default zone. */
    private Instant at(DayOfWeek day, LocalTime time) {
        ZonedDateTime mondayBase = ZonedDateTime.of(2026, 8, 10, 0, 0, 0, 0, ZoneId.systemDefault()); // a Monday
        return mondayBase.with(day).with(time).toInstant();
    }

    private PeerSchedule schedule(int weekdayMask, LocalTime from, LocalTime to) {
        PeerSchedule s = new PeerSchedule();
        s.weekdayMask = weekdayMask;
        s.activeFrom = from.toString();
        s.activeTo = to.toString();
        return s;
    }

    private static final int MON = 1 << 0;
    private static final int WED = 1 << 2;
    private static final int SAT = 1 << 5;
    private static final int SUN = 1 << 6;

    @Test
    void withinNormalWindow_isActive() {
        PeerSchedule s = schedule(MON, LocalTime.of(8, 0), LocalTime.of(18, 0));
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.MONDAY, LocalTime.of(12, 0)))).isTrue();
    }

    @Test
    void outsideNormalWindow_isInactive() {
        PeerSchedule s = schedule(MON, LocalTime.of(8, 0), LocalTime.of(18, 0));
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.MONDAY, LocalTime.of(19, 0)))).isFalse();
    }

    @Test
    void windowStart_isInclusive() {
        PeerSchedule s = schedule(MON, LocalTime.of(8, 0), LocalTime.of(18, 0));
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.MONDAY, LocalTime.of(8, 0)))).isTrue();
    }

    @Test
    void windowEnd_isExclusive() {
        PeerSchedule s = schedule(MON, LocalTime.of(8, 0), LocalTime.of(18, 0));
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.MONDAY, LocalTime.of(18, 0)))).isFalse();
    }

    @Test
    void wrongWeekday_isInactiveEvenDuringTheTimeWindow() {
        PeerSchedule s = schedule(MON, LocalTime.of(8, 0), LocalTime.of(18, 0));
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.TUESDAY, LocalTime.of(12, 0)))).isFalse();
    }

    @Test
    void overnightWindow_activeLateAtNight() {
        // 22:00 -> 06:00, spans midnight.
        PeerSchedule s = schedule(WED, LocalTime.of(22, 0), LocalTime.of(6, 0));
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.WEDNESDAY, LocalTime.of(23, 30)))).isTrue();
    }

    @Test
    void overnightWindow_activeEarlyNextMorning_governedByYesterdaysBit() {
        // mask=WED means the window starts Wednesday 22:00 and continues into
        // Thursday's early morning — so Thursday 03:00 must be active (checked
        // against Wednesday's bit, the day the window actually started on),
        // while Wednesday's own early morning (before any Tuesday-started
        // window would apply) must NOT be active since Tuesday isn't in the mask.
        PeerSchedule s = schedule(WED, LocalTime.of(22, 0), LocalTime.of(6, 0));
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.THURSDAY, LocalTime.of(3, 0)))).isTrue();
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.WEDNESDAY, LocalTime.of(3, 0)))).isFalse();
    }

    @Test
    void overnightWindow_inactiveInTheMiddleOfTheDay() {
        PeerSchedule s = schedule(WED, LocalTime.of(22, 0), LocalTime.of(6, 0));
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.WEDNESDAY, LocalTime.of(12, 0)))).isFalse();
    }

    @Test
    void zeroWidthWindow_neverActive() {
        PeerSchedule s = schedule(MON, LocalTime.of(9, 0), LocalTime.of(9, 0));
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.MONDAY, LocalTime.of(9, 0)))).isFalse();
    }

    @Test
    void weekendMask_coversBothSaturdayAndSunday() {
        PeerSchedule s = schedule(SAT | SUN, LocalTime.of(0, 0), LocalTime.of(23, 59));
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.SATURDAY, LocalTime.of(10, 0)))).isTrue();
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.SUNDAY, LocalTime.of(10, 0)))).isTrue();
        assertThat(svc.evaluateWindow(s, at(DayOfWeek.MONDAY, LocalTime.of(10, 0)))).isFalse();
    }
}
