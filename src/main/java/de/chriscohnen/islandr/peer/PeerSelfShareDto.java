package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class PeerSelfShareDto {

    private PeerSelfShareDto() {}

    public record CreateRequest(
            @NotBlank @Email String targetEmail,
            // Above the well-known/registered-port line on purpose (TASK.md
            // design note) — this is for a dev server or similar the owner
            // is running themselves, never something already covered by the
            // ACL model's own resource ports.
            @Min(1025) @Max(65535) int port,
            // Same ladder ReservationService already offers (60/240/1440/10080
            // minutes) — reused rather than inventing a second one.
            int durationMinutes
    ) {}

    public record Response(
            String id,
            String ownerPeerId,
            String targetUserId,
            String targetUserName,
            String targetUserEmail,
            int port,
            Instant createdAt,
            Instant validUntil,
            Instant revokedAt,
            boolean live
    ) {
        public static Response from(PeerSelfShare s, Instant now) {
            User target = User.findById(s.targetUserId);
            return new Response(
                    s.id, s.ownerPeerId, s.targetUserId,
                    target == null ? null : target.name,
                    target == null ? null : target.email,
                    s.port, s.createdAt, s.validUntil, s.revokedAt,
                    s.isLiveAt(now));
        }
    }
}
