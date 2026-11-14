package de.chriscohnen.islandr.backup;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** backup-schedule: when a backup is due — a pure rule, tested without a clock or a scheduler. */
class BackupScheduleTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final LocalTime THREE = LocalTime.of(3, 0);

    private static Instant at(String iso) { return Instant.parse(iso); }

    @Test
    void dailySlot_isTodaysTime_oncePassed_andYesterdaysBefore() {
        assertThat(BackupSchedule.lastSlot(at("2026-11-14T10:00:00Z"), "daily", THREE, 1, UTC))
                .isEqualTo(at("2026-11-14T03:00:00Z"));
        assertThat(BackupSchedule.lastSlot(at("2026-11-14T02:59:00Z"), "daily", THREE, 1, UTC))
                .isEqualTo(at("2026-11-13T03:00:00Z"));
    }

    @Test
    void weeklySlot_isTheLatestChosenWeekday() {
        // 2026-11-14 is a Saturday; Monday = 1
        assertThat(BackupSchedule.lastSlot(at("2026-11-14T10:00:00Z"), "weekly", THREE, 1, UTC))
                .isEqualTo(at("2026-11-09T03:00:00Z"));
        // on the chosen day but before the time: the week before
        assertThat(BackupSchedule.lastSlot(at("2026-11-09T02:00:00Z"), "weekly", THREE, 1, UTC))
                .isEqualTo(at("2026-11-02T03:00:00Z"));
    }

    @Test
    void offHasNoSlot_andIsNeverDue() {
        assertThat(BackupSchedule.lastSlot(Instant.now(), "off", THREE, 1, UTC)).isNull();
        assertThat(BackupSchedule.due(Instant.now(), "off", THREE, 1, UTC, at("2020-01-01T00:00:00Z"), null)).isFalse();
    }

    @Test
    void dueWhenTheLatestSlotIsNewerThanTheLastRunAndTheSwitchOn() {
        Instant now = at("2026-11-14T10:00:00Z");
        assertThat(BackupSchedule.due(now, "daily", THREE, 1, UTC, at("2026-11-10T00:00:00Z"), at("2026-11-13T03:00:30Z"))).isTrue();
    }

    @Test
    void notDueAgainOnceTheSlotHasRun() {
        Instant now = at("2026-11-14T10:00:00Z");
        assertThat(BackupSchedule.due(now, "daily", THREE, 1, UTC, at("2026-11-10T00:00:00Z"), at("2026-11-14T03:00:20Z"))).isFalse();
    }

    @Test
    void aScheduleSwitchedOnAfterTodaysSlotDoesNotCatchUpForIt() {
        Instant now = at("2026-11-14T15:05:00Z");
        Instant switchedOn = at("2026-11-14T15:00:00Z");
        assertThat(BackupSchedule.due(now, "daily", THREE, 1, UTC, switchedOn, null)).isFalse();
        // ... but tomorrow's slot is due
        assertThat(BackupSchedule.due(at("2026-11-15T03:01:00Z"), "daily", THREE, 1, UTC, switchedOn, null)).isTrue();
    }

    @Test
    void aHubThatWasOffOverSeveralSlotsCatchesUpOnce() {
        Instant lastRun = at("2026-11-10T03:00:10Z");
        Instant back = at("2026-11-14T10:00:00Z");
        assertThat(BackupSchedule.due(back, "daily", THREE, 1, UTC, at("2026-11-01T00:00:00Z"), lastRun)).isTrue();
        // one catch-up run later, nothing more is due until the next slot
        assertThat(BackupSchedule.due(back.plusSeconds(60), "daily", THREE, 1, UTC, at("2026-11-01T00:00:00Z"), back)).isFalse();
    }

    @Test
    void aScheduleWithNoReferencePointNeverFires() {
        assertThat(BackupSchedule.due(Instant.now(), "daily", THREE, 1, UTC, null, null)).isFalse();
    }

    @Test
    void theTimeIsLocalToTheHub_notUtc() {
        ZoneId berlin = ZoneId.of("Europe/Berlin"); // UTC+1 in November
        Instant slot = BackupSchedule.lastSlot(at("2026-11-14T10:00:00Z"), "daily", THREE, 1, berlin);
        assertThat(ZonedDateTime.ofInstant(slot, berlin).toLocalTime()).isEqualTo(THREE);
        assertThat(slot).isEqualTo(at("2026-11-14T02:00:00Z"));
    }
}
