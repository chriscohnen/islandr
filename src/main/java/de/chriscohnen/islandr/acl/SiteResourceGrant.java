package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A direct grant from one Site (its gateway peer's CIDR) to one Resource,
 * bypassing the Role model entirely — the site-subject counterpart to
 * {@link UserResourceGrant} (ADR-0024). Same tri-state shape:
 * {@code allPorts=true} is the wildcard variant; {@code allPorts=false}
 * together with rows in {@code site_resource_grant_ports} means "only
 * these specific ports". Unlike a peer-level grant, this widens access to
 * every host inside the site's CIDR, not just its gateway peer's own IP.
 */
@Entity
@Table(name = "site_resource_grants")
public class SiteResourceGrant extends PanacheEntityBase {
    @Id @Column(name = "id", nullable = false, length = 36)
    public String id;
    @Column(name = "site_id", nullable = false, length = 36)
    public String siteId;
    @Column(name = "resource_id", nullable = false, length = 36)
    public String resourceId;
    @Column(name = "all_ports", nullable = false)
    public boolean allPorts;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static SiteResourceGrant createNew(String siteId, String resourceId, boolean allPorts) {
        SiteResourceGrant g = new SiteResourceGrant();
        g.id = UUID.randomUUID().toString();
        g.siteId = siteId;
        g.resourceId = resourceId;
        g.allPorts = allPorts;
        g.createdAt = Instant.now();
        return g;
    }

    public static SiteResourceGrant findBySiteResource(String siteId, String resourceId) {
        return find("siteId = ?1 and resourceId = ?2", siteId, resourceId).firstResult();
    }
}
