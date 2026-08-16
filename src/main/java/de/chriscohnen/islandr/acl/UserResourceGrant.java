package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A direct grant from one User to one Resource, bypassing the Role model
 * entirely (ADR-0024). Same tri-state shape as {@link RoleResourceGrant}:
 * {@code allPorts=true} is the wildcard variant; {@code allPorts=false}
 * together with rows in {@code user_resource_grant_ports} means "only
 * these specific ports".
 */
@Entity
@Table(name = "user_resource_grants")
public class UserResourceGrant extends PanacheEntityBase {
    @Id @Column(name = "id", nullable = false, length = 36)
    public String id;
    @Column(name = "user_id", nullable = false, length = 36)
    public String userId;
    @Column(name = "resource_id", nullable = false, length = 36)
    public String resourceId;
    @Column(name = "all_ports", nullable = false)
    public boolean allPorts;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static UserResourceGrant createNew(String userId, String resourceId, boolean allPorts) {
        UserResourceGrant g = new UserResourceGrant();
        g.id = UUID.randomUUID().toString();
        g.userId = userId;
        g.resourceId = resourceId;
        g.allPorts = allPorts;
        g.createdAt = Instant.now();
        return g;
    }

    public static UserResourceGrant findByUserResource(String userId, String resourceId) {
        return find("userId = ?1 and resourceId = ?2", userId, resourceId).firstResult();
    }
}
