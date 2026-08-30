package de.chriscohnen.islandr.acl;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public final class ReservationDto {

    private ReservationDto() {}

    public record CreateRequest(
            @NotBlank String resourceId,
            // Minutes. Validated against ReservationService.DURATION_CHOICES
            // rather than a range, so the API and the portal's picker cannot
            // drift apart.
            int minutes
    ) {}

    public record Response(
            String id,
            String resourceId,
            String resourceName,
            String siteName,
            String userId,
            String userName,
            String status,
            int requestedMinutes,
            Instant requestedAt,
            Instant startsAt,
            Instant endsAt,
            String decidedBy,
            Instant decidedAt
    ) {}

    /** One current holder, for the at-capacity rejection and the portal's "in use by" line. */
    public record HolderResponse(String userId, String userName, Instant until) {}

    /**
     * 409 body when a request is refused for want of a free slot. Names the
     * holders on purpose (issue #72): a bare "no" gives the requester nothing
     * to act on, whereas "Jane until 14:30" lets them go and coordinate.
     */
    public record AtCapacityResponse(
            String error,
            List<HolderResponse> holders
    ) {
        public static AtCapacityResponse of(List<ReservationService.Holder> holders) {
            return new AtCapacityResponse("at_capacity",
                    holders.stream()
                            .map(h -> new HolderResponse(h.userId(), h.userName(), h.until()))
                            .toList());
        }
    }
}
