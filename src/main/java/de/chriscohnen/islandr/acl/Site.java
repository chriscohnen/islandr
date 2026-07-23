package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import de.chriscohnen.islandr.validation.ValidCidr;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

/**
 * Organisational grouping of resources (typically one remote network reachable
 * through the hub). The {@code cidr} is informational — the UI groups
 * resources by site and renders the CIDR next to the site name — it does not
 * participate in nftables rule generation (ADR-0006).
 */
@Entity
@Table(name = "sites")
public class Site extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @NotBlank
    @Column(name = "name", nullable = false, unique = true)
    public String name;

    @NotBlank
    @ValidCidr
    @Column(name = "cidr", nullable = false, length = 50)
    public String cidr;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    /**
     * Optional peer that routes traffic for this site's CIDR. Null = no gateway configured.
     * Geocoding lives on that peer, not here (see {@link de.chriscohnen.islandr.peer.Peer#lat}) —
     * a site is a logical CIDR grouping with no physical location of its own, and one gateway
     * peer can serve more than one site.
     */
    @Column(name = "gateway_peer_id", length = 36)
    public String gatewayPeerId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** Last time any mutable field on this site changed (name, CIDR, gateway, …). */
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public static Site createNew(String name, String cidr, String description) {
        Site s = new Site();
        s.id = UUID.randomUUID().toString();
        s.name = name;
        s.cidr = cidr;
        s.description = description;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        return s;
    }
}
