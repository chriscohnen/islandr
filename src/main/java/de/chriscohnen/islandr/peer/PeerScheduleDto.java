package de.chriscohnen.islandr.peer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalTime;

public final class PeerScheduleDto {

    /** weekdayMask: bit0=Monday...bit6=Sunday, must be 1-127 (at least one day set). */
    public record Request(
            @Min(1) @Max(127) int weekdayMask,
            @NotNull LocalTime activeFrom,
            @NotNull LocalTime activeTo
    ) {}

    public record Response(
            String peerId, int weekdayMask, LocalTime activeFrom, LocalTime activeTo,
            Instant createdAt, Instant updatedAt
    ) {
        public static Response from(PeerSchedule s) {
            return new Response(s.peerId, s.weekdayMask, s.activeFromTime(), s.activeToTime(),
                    s.createdAt, s.updatedAt);
        }
    }

    private PeerScheduleDto() {}
}
