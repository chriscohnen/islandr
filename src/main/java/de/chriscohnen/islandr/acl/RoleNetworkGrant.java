package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A role's permission to reach every host in a site's CIDR — "this role's
 * peers reach the whole Homeoffice network" — instead of one grant per
 * concrete resource or one grant per resource type. See ADR-0029: always
 * full-reach (every port, every protocol), no port columns, role-only (no
 * direct-user counterpart).
 */
@Entity
@Table(name = "role_network_grants")
public class RoleNetworkGrant extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @Column(name = "role_id", nullable = false, length = 36)
    public String roleId;

    @Column(name = "site_id", nullable = false, length = 36)
    public String siteId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static RoleNetworkGrant createNew(String roleId, String siteId) {
        RoleNetworkGrant g = new RoleNetworkGrant();
        g.id = UUID.randomUUID().toString();
        g.roleId = roleId;
        g.siteId = siteId;
        g.createdAt = Instant.now();
        return g;
    }

    public static RoleNetworkGrant findByRoleSite(String roleId, String siteId) {
        return find("roleId = ?1 and siteId = ?2", roleId, siteId).firstResult();
    }
}
