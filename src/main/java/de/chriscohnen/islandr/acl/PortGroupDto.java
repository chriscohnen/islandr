package de.chriscohnen.islandr.acl;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public final class PortGroupDto {

    public record Response(
            String id,
            String name,
            String description,
            List<ResourceDto.PortResponse> members,  // shape match — easier on the UI
            Instant createdAt
    ) {
        public static Response from(PortGroup g, List<ResourceDto.PortResponse> members) {
            return new Response(g.id, g.name, g.description, members, g.createdAt);
        }
    }

    public record UpsertRequest(
            @NotBlank String name,
            String description,
            // PUT replaces the full member set — the UI shows the group + a
            // table of port rows the admin can edit inline. Bean Validation
            // descends into the element type via @Valid on the list itself.
            @NotNull @Valid List<ResourceDto.PortRequest> members
    ) {}

    /**
     * Body for POST /api/v1/resources/{id}/ports/apply-group. The portGroupId
     * names the template; the server reads its current members and appends
     * any that don't already exist on the resource (snapshot semantics).
     */
    public record ApplyRequest(@NotBlank String portGroupId) {}

    /** Response after applying: how many ports were added vs. already present. */
    public record ApplyResponse(int added, int skippedExisting) {}

    private PortGroupDto() {}
}
