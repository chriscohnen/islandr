package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.validation.ValidIpAddress;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

public final class ResourceDto {

    public record Response(
            String id,
            String siteId,
            String name,
            String ip,
            String description,
            String type,
            // Optional DNS label for the resource-name resolver (ADR-0023, MVP).
            // Null = this resource never resolves through it.
            String dnsName,
            // When true, resolves as "<dnsName>.<zone>" directly — no site
            // subdomain (ADR-0023 follow-up). Meaningless unless dnsName is set.
            boolean dnsFlat,
            List<PortResponse> ports,
            Instant createdAt
    ) {
        public static Response from(Resource r, List<PortResponse> ports) {
            return new Response(r.id, r.siteId, r.name, r.ip, r.description, r.type, r.dnsName, r.dnsFlat,
                    ports, r.createdAt);
        }
    }

    public record PortResponse(
            String id,
            int port,
            Integer portEnd,
            String transport,
            String protocol,
            String label,
            String pathPrefix,
            boolean rdpClipboard,
            boolean rdpFileTransfer,
            String rdpAccessMode,
            // Exclusive-capacity config (issue #72). maxConcurrentUsers null =
            // unlimited, i.e. not reservable — the default for every port that
            // predates #72.
            Integer maxConcurrentUsers,
            Integer maxReservationMinutes,
            boolean autoApproveReservations,
            Instant createdAt
    ) {
        public static PortResponse from(ResourcePort p) {
            return new PortResponse(p.id, p.port, p.portEnd, p.transport, p.protocol, p.label,
                    p.pathPrefix, p.rdpClipboard, p.rdpFileTransfer, p.rdpAccessMode,
                    p.maxConcurrentUsers, p.maxReservationMinutes, p.autoApproveReservations,
                    p.createdAt);
        }
    }

    public record UpsertRequest(
            @NotBlank String name,
            @NotBlank @ValidIpAddress
            String ip,
            String description,
            // Optional in the request; defaults to 'computer' if null/blank.
            // The CHECK constraint in the DB rejects anything outside the
            // allowed set, surfacing as HTTP 500 — the UI must restrict the
            // input to the documented set (see V13 migration).
            @Pattern(regexp = "^(computer|router|printer|nas|camera|iot|virt-host|rackserver|kvm|management|other)?$",
                    message = "type must be one of: computer, router, printer, nas, camera, iot, virt-host, rackserver, kvm, management, other")
            String type,

            // Optional — DNS label for the resource-name resolver (ADR-0023).
            // Blank/null = never resolves. Lowercased and stripped by ResourceService.
            @Pattern(regexp = "^$|^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$",
                    message = "must be a DNS label (letters, digits, hyphens; not starting/ending with a hyphen)")
            String dnsName,

            // Optional — true resolves this resource directly under the zone
            // apex, no site subdomain (ADR-0023 follow-up). Ignored when dnsName
            // is blank.
            boolean dnsFlat
    ) {}

    /**
     * Resource as seen by the end-user in their self-service portal.
     * Only the ports this user is actually granted are included
     * (either all of them when allPorts=true, or the limited set).
     */
    public record MyAccessResource(
            String id,
            String siteId,
            String siteName,
            String name,
            String ip,
            String description,
            String type,
            List<MyAccessPort> grantedPorts
    ) {}

    /**
     * One granted port as the portal sees it (issue #72): the port itself plus
     * whatever the *calling user* needs to know about its exclusive-capacity
     * state. Deliberately a separate shape from {@link PortResponse}, which
     * the admin views share — "my reservation" has no meaning in a listing
     * that is not scoped to one person.
     *
     * <p>Flat rather than wrapping a PortResponse so the portal template keeps
     * addressing {@code p.port} / {@code p.protocol} directly.
     */
    public record MyAccessPort(
            String id,
            int port,
            Integer portEnd,
            String transport,
            String protocol,
            String label,
            String pathPrefix,
            boolean rdpClipboard,
            boolean rdpFileTransfer,
            String rdpAccessMode,
            // Null maxConcurrentUsers = not reservable; the rest of the
            // reservation fields are then meaningless. That is the default.
            Integer maxConcurrentUsers,
            Integer maxReservationMinutes,
            boolean autoApproveReservations,
            // The caller's own open reservation on this port, if any.
            String myReservationId,
            String myReservationStatus,
            java.time.Instant myReservationEndsAt,
            // Who holds a slot right now, so the portal can say "in use by
            // Jane until 14:30" — and hand over an address to ask.
            List<ReservationHolder> holders
    ) {}

    /** One current holder of a capacity-limited port (issue #72). */
    public record ReservationHolder(String userId, String userName, String userEmail,
                                    java.time.Instant until) {}

    /**
     * Portal view for one user: their granted resources plus the portal-level flags
     * they need but cannot read from the admin-only settings endpoint. Currently just
     * {@code ironRdpEnabled}, which gates the "open in browser" RDP button.
     */
    public record MyAccessResponse(
            boolean ironRdpEnabled,
            List<MyAccessResource> resources
    ) {}

    public record PortRequest(
            @Min(0) @Max(65535) int port,
            Integer portEnd,
            @NotBlank
            @Pattern(regexp = "^(tcp|udp|both)$", message = "transport must be 'tcp', 'udp', or 'both'")
            String transport,
            @NotBlank String protocol,
            String label,
            @Pattern(regexp = "^(/[^\\s]*)?$", message = "pathPrefix must start with /")
            String pathPrefix,
            // RDP-specific — ignored for non-RDP protocols
            boolean rdpClipboard,
            boolean rdpFileTransfer,
            @Pattern(regexp = "^(native|web-only)$", message = "rdpAccessMode must be 'native' or 'web-only'")
            String rdpAccessMode,

            // Exclusive-capacity config (issue #72). Nullable on purpose: null
            // maxConcurrentUsers means "not capacity-limited", which is both
            // the default and how an admin turns the feature back off.
            @Min(value = 1, message = "maxConcurrentUsers must be at least 1")
            Integer maxConcurrentUsers,
            @Min(value = 5, message = "maxReservationMinutes must be at least 5")
            Integer maxReservationMinutes,
            // Boxed on purpose: a record's primitive boolean deserialises to
            // FALSE when the client omits the key, which would silently mean
            // "every request needs an admin decision" — the opposite of the
            // intended default. Null here is read as true by the service.
            Boolean autoApproveReservations
    ) {}

    /** Bulk-delete request: the resource ids to remove (missing ids are skipped). */
    public record BulkDeleteRequest(List<String> ids) {}

    public record BulkDeleteResult(int deleted) {}

    private ResourceDto() {}
}
