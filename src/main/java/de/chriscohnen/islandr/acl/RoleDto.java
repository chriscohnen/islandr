package de.chriscohnen.islandr.acl;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public final class RoleDto {

    public record Response(
            String id,
            String name,
            String description,
            int memberCount,
            int grantCount,
            boolean autoAll,
            Instant createdAt
    ) {
        public static Response from(Role r, int memberCount, int grantCount) {
            return new Response(r.id, r.name, r.description, memberCount, grantCount, r.autoAll, r.createdAt);
        }
    }

    public record UpsertRequest(
            @NotBlank String name,
            String description
    ) {}

    public record MembershipRequest(
            // List of user IDs that should be in this role after the call.
            // PUT replaces the full set — convenient for the admin UI which
            // shows the role + a checklist of users.
            List<String> userIds
    ) {}

    public record MemberResponse(String id, String name, String email) {}

    /**
     * Frontend matrix payload: one row per role × resource cell. allPorts=true
     * means the cell shows "ⓐ", non-empty portIds means "N" (count), absence
     * means "∅". Used by GET /api/v1/acl/matrix.
     */
    public record GrantCell(
            String roleId,
            String resourceId,
            boolean allPorts,
            List<String> portIds
    ) {}

    /**
     * One element of a PUT body when the admin clicks "Änderungen anwenden"
     * on the matrix. The frontend sends only changed cells.
     */
    public record GrantUpdate(
            @NotBlank String roleId,
            @NotBlank String resourceId,
            boolean allPorts,
            List<String> portIds   // ignored when allPorts=true
    ) {}

    public record MatrixApplyRequest(List<GrantUpdate> grants) {}

    private RoleDto() {}
}
