package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import de.chriscohnen.islandr.validation.ValidCidr;
import de.chriscohnen.islandr.validation.ValidIpAddress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
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

    /** Explicit DNS label for this network in the resource-name resolver
     *  (ADR-0023) — e.g. resources resolve as {@code <resource>.<subdomain>.<zone>}.
     *  Null/blank = derived live from {@code name} (DnsQueryHandler.slugify)
     *  every query, the original behavior; renaming the site then also renames
     *  every resource's DNS name. Setting this decouples the two. */
    @Pattern(regexp = "^$|^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$",
            message = "must be a lowercase DNS label (letters, digits, hyphens; not starting/ending with a hyphen)")
    @Column(name = "subdomain", length = 63)
    public String subdomain;

    /** Optional local DNS server (usually the LAN router, e.g. a FRITZ!Box)
     *  that device discovery queries with a targeted reverse-DNS (PTR)
     *  lookup to suggest a resource name (issue #45). Null = discovery falls
     *  back to the JVM system resolver, the original behavior. */
    @ValidIpAddress
    @Column(name = "dns_server_ip", length = 45)
    public String dnsServerIp;

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

    /**
     * CIDR of every site whose gateway peer exists and is enabled. Used by
     * {@link de.chriscohnen.islandr.peer.AllowedIpsCalculator} (issue #33) to
     * fill in routes for sites a split-tunnel supernet doesn't cover — never
     * for nftables rule generation (see class javadoc).
     */
    public static List<String> enabledGatewayCidrs() {
        return Site.<Site>listAll().stream()
                .filter(site -> site.gatewayPeerId != null)
                .filter(site -> {
                    de.chriscohnen.islandr.peer.Peer gw = de.chriscohnen.islandr.peer.Peer.findById(site.gatewayPeerId);
                    return gw != null && gw.enabled;
                })
                .map(site -> site.cidr)
                .toList();
    }
}
