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

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static ResourcePort createNew(String resourceId, int port, Integer portEnd,
                                         String transport, String protocol, String label) {
        ResourcePort p = new ResourcePort();
        p.id = UUID.randomUUID().toString();
        p.resourceId = resourceId;
        p.port = port;
        p.portEnd = portEnd;
        p.transport = transport;
        p.protocol = protocol;
        p.label = label;
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
