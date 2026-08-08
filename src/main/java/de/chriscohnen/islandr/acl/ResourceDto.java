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
            return new Response(r.id, r.siteId, r.name, r.ip, r.description, r.type, r.dnsName, r.dnsFlat, ports, r.createdAt);
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
            Instant createdAt
    ) {
        public static PortResponse from(ResourcePort p) {
            return new PortResponse(p.id, p.port, p.portEnd, p.transport, p.protocol, p.label,
                    p.pathPrefix, p.rdpClipboard, p.rdpFileTransfer, p.rdpAccessMode, p.createdAt);
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
            List<PortResponse> grantedPorts
    ) {}

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
            String rdpAccessMode
    ) {}

    /** Bulk-delete request: the resource ids to remove (missing ids are skipped). */
    public record BulkDeleteRequest(List<String> ids) {}

    public record BulkDeleteResult(int deleted) {}

    private ResourceDto() {}
}
