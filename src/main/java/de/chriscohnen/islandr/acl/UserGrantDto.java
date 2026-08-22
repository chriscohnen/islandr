package de.chriscohnen.islandr.acl;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public class UserGrantDto {
    public record Update(
            @NotBlank String userId,
            @NotBlank String resourceId,
            boolean allPorts,
            List<String> portIds,   // ignored when allPorts=true
            // Issue #70 — ad-hoc temporary grant. Null = permanent (unchanged
            // default). Auto-revoked by UserGrantExpiryJob once this passes.
            Instant validUntil
    ) {}

    /** One row for the ACL page's direct-grants list (GET /api/v1/acl/user-grants). */
    public record ListItem(
            String userId, String userName,
            String resourceId, String resourceName, String siteName,
            boolean allPorts, List<String> portLabels,
            Instant validUntil) {}
}
