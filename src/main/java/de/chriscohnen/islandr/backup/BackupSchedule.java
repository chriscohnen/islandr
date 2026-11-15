package de.chriscohnen.islandr.backup;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * When an automatic backup is due (backup-schedule) — pure, so the rule can be
 * tested without a clock or a scheduler.
 *
 * <p>A schedule is a series of slots ("every day at 03:00", "Mondays at 03:00").
 * A backup is due when the latest slot that has already passed is newer than
 * both the last run and the moment the schedule was switched on. The second
 * condition is what keeps a schedule enabled at 15:00 from firing at once for
 * the 03:00 slot it never had; the first is what makes a hub that was off over
 * the slot catch up once when it comes back, rather than once per missed slot.
 */
final class BackupSchedule {

    static final String OFF = "off";
    static final String DAILY = "daily";
    static final String WEEKLY = "weekly";

    private BackupSchedule() {}

    /** The latest slot at or before {@code now}, or null when the schedule is off. */
    static Instant lastSlot(Instant now, String schedule, LocalTime time, int dayOfWeek, ZoneId zone) {
        if (DAILY.equals(schedule)) {
            ZonedDateTime today = ZonedDateTime.of(LocalDate.ofInstant(now, zone), time, zone);
            return (today.toInstant().isAfter(now) ? today.minusDays(1) : today).toInstant();
        }
        if (WEEKLY.equals(schedule)) {
            LocalDate date = LocalDate.ofInstant(now, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.of(dayOfWeek)));
            ZonedDateTime slot = ZonedDateTime.of(date, time, zone);
            return (slot.toInstant().isAfter(now) ? slot.minusWeeks(1) : slot).toInstant();
        }
        return null;
    }

    static boolean due(Instant now, String schedule, LocalTime time, int dayOfWeek, ZoneId zone,
                       Instant since, Instant lastRun) {
        Instant slot = lastSlot(now, schedule, time, dayOfWeek, zone);
        if (slot == null) return false;
        Instant newest = since;
        if (lastRun != null && (newest == null || lastRun.isAfter(newest))) newest = lastRun;
        // No reference point at all would mean a schedule nobody switched on: never fire.
        return newest != null && slot.isAfter(newest);
    }
}
