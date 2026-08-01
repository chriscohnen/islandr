package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A role's permission to reach every resource of a given type within a site
 * — "all printers in Homeoffice" — instead of one grant per concrete
 * resource. See migration V51 for the additive-only, always-all-ports
 * semantics this carries: a type-grant can only widen access (never
 * override or exclude an individual resource) and always covers every port,
 * matching {@link RoleResourceGrant}'s {@code allPorts=true} shape.
 *
 * <p>Deliberately simpler than {@link RoleResourceGrant}: no port-subset
 * variant, no per-resource exclusion. Both were considered and dropped —
 * see the ACL type-grants scoping decision (2026-07-28) — to keep the
 * enforcement expansion (RuleBuilder, MyAccessResource, RdpGrantService)
 * a straightforward "resolve matching resources, treat like an all-ports
 * grant" instead of a second conflict-resolution model alongside the
 * existing per-resource one.
 */
@Entity
@Table(name = "role_resource_type_grants")
public class RoleResourceTypeGrant extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @Column(name = "role_id", nullable = false, length = 36)
    public String roleId;

    @Column(name = "site_id", nullable = false, length = 36)
    public String siteId;

    @Column(name = "resource_type", nullable = false, length = 16)
    public String resourceType;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static RoleResourceTypeGrant createNew(String roleId, String siteId, String resourceType) {
        RoleResourceTypeGrant g = new RoleResourceTypeGrant();
        g.id = UUID.randomUUID().toString();
        g.roleId = roleId;
        g.siteId = siteId;
        g.resourceType = resourceType;
        g.createdAt = Instant.now();
        return g;
    }

    public static RoleResourceTypeGrant findByRoleSiteType(String roleId, String siteId, String resourceType) {
        return find("roleId = ?1 and siteId = ?2 and resourceType = ?3", roleId, siteId, resourceType).firstResult();
    }
}
