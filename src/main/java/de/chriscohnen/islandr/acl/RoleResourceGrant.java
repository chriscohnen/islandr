package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A role's permission to reach a resource. {@code allPorts=true} is the
 * wildcard variant: the role can reach every current and future port of
 * the resource. {@code allPorts=false} together with rows in the join table
 * {@code role_resource_grant_ports} means "only these specific ports".
 *
 * <p>Per ADR-0006 R-054, all-ports grants quietly widen access when new
 * ports are later added to the resource. The matrix UI surfaces this with
 * a "ⓐ" label and a tooltip; the audit log emits a row whenever a new
 * port is added under an existing ⓐ-grant.
 */
@Entity
@Table(name = "role_resource_grants")
public class RoleResourceGrant extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @Column(name = "role_id", nullable = false, length = 36)
    public String roleId;

    @Column(name = "resource_id", nullable = false, length = 36)
    public String resourceId;

    @Column(name = "all_ports", nullable = false)
    public boolean allPorts;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static RoleResourceGrant createNew(String roleId, String resourceId, boolean allPorts) {
        RoleResourceGrant g = new RoleResourceGrant();
        g.id = UUID.randomUUID().toString();
        g.roleId = roleId;
        g.resourceId = resourceId;
        g.allPorts = allPorts;
        g.createdAt = Instant.now();
        return g;
    }

    public static RoleResourceGrant findByRoleResource(String roleId, String resourceId) {
        return find("roleId = ?1 and resourceId = ?2", roleId, resourceId).firstResult();
    }
}
