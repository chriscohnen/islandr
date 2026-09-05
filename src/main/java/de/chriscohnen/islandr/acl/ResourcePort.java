package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * One reachable {@code (transport, port)} on a {@link Resource}. The
 * {@code protocol} field is a UI label (RDP / SSH / SFTP / HTTP / CUSTOM) and
 * carries no enforcement weight — see ADR-0006 §"What this does not protect
 * against". {@code label} is free-form text the admin can use to disambiguate
 * two ports with the same protocol ("RDP for IT" vs. "RDP for VPN").
 */
@Entity
@Table(name = "resource_ports")
public class ResourcePort extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @NotBlank
    @Column(name = "resource_id", nullable = false, length = 36)
    public String resourceId;

    /** 0 = all ports (no dport filter). 1-65535 = specific port or range start. */
    @Min(0) @Max(65535)
    @Column(name = "port", nullable = false)
    public int port;

    /** Range end. Null = single port (or all when port=0). Must be > port when set. */
    @Column(name = "port_end")
    public Integer portEnd;

    @NotBlank
    @Pattern(regexp = "^(tcp|udp|both)$", message = "transport must be 'tcp', 'udp', or 'both'")
    @Column(name = "transport", nullable = false, length = 8)
    public String transport;

    @NotBlank
    @Column(name = "protocol", nullable = false, length = 32)
    public String protocol;

    @Column(name = "label", length = 255)
    public String label;

    @Column(name = "path_prefix", length = 255)
    public String pathPrefix;

    // RDP-specific options (only meaningful when protocol = "RDP")
    @Column(name = "rdp_clipboard", nullable = false, columnDefinition = "INTEGER")
    public boolean rdpClipboard = true;

    @Column(name = "rdp_file_transfer", nullable = false, columnDefinition = "INTEGER")
    public boolean rdpFileTransfer = false;

    // "native" = nftables opens dport, client connects directly
    // "web-only" = nftables blocks dport; access only via IronRDP browser proxy
    @Column(name = "rdp_access_mode", nullable = false, length = 16)
    public String rdpAccessMode = "native";

    /** Exclusive-capacity limit (issue #72): how many users may hold this
     *  port at the same time. {@code null} — the default, and every port that
     *  existed before #72 — means unlimited, i.e. a grant alone reaches it,
     *  exactly as before. Any non-null value opts the port into the
     *  reservation layer: a standing grant then establishes only
     *  *eligibility*, and an active {@link ResourceReservation} on this port
     *  is additionally required to actually reach it.
     *
     *  <p>Deliberately per port rather than per resource: a host can have one
     *  seat on RDP while its SSH port stays freely usable, and locking the
     *  whole machine for that would be wrong. */
    @Column(name = "max_concurrent_users")
    public Integer maxConcurrentUsers;

    /** Ceiling on a single self-service reservation of this port, in minutes.
     *  {@code null} = no ceiling beyond the duration picker's own options. */
    @Column(name = "max_reservation_minutes")
    public Integer maxReservationMinutes;

    /** When true (default), a request that fits the remaining capacity is
     *  granted immediately; when false every request waits for an admin
     *  decision even on an idle port. */
    @Column(name = "auto_approve_reservations", nullable = false, columnDefinition = "INTEGER")
    public boolean autoApproveReservations = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** True when this port takes part in the reservation layer at all. */
    public boolean isCapacityLimited() {
        return maxConcurrentUsers != null;
    }

    public static ResourcePort createNew(String resourceId, int port, Integer portEnd,
                                         String transport, String protocol, String label,
                                         String pathPrefix, boolean rdpClipboard,
                                         boolean rdpFileTransfer, String rdpAccessMode) {
        ResourcePort p = new ResourcePort();
        p.id = UUID.randomUUID().toString();
        p.resourceId = resourceId;
        p.port = port;
        p.portEnd = portEnd;
        p.transport = transport;
        p.protocol = protocol;
        p.label = label;
        p.pathPrefix = pathPrefix;
        p.rdpClipboard = rdpClipboard;
        p.rdpFileTransfer = rdpFileTransfer;
        p.rdpAccessMode = rdpAccessMode != null ? rdpAccessMode : "native";
        p.createdAt = Instant.now();
        return p;
    }

    /** Human-readable port spec: "all", "80", or "8080-8090". */
    public String portSpec() {
        if (port == 0) return "all";
        if (portEnd != null) return port + "-" + portEnd;
        return String.valueOf(port);
    }
}
