package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

/**
 * RBAC0 role (ADR-0006). Roles attach users to {@link RoleResourceGrant}s.
 *
 * <p>Distinct from the {@code users.is_admin} flag (V6): {@code is_admin}
 * controls Admin-Console access, RBAC roles control which resources the user's
 * peers may reach through the tunnel. Both axes are independent (an admin
 * without a role can reach the Admin Console but has no VPN-side resource
 * access until they're added to a role too).
 */
@Entity
@Table(name = "roles")
public class Role extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @NotBlank
    @Column(name = "name", nullable = false, unique = true)
    public String name;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /**
     * Auto-membership: when true, every user is an implicit member — present and
     * future — with no user_roles rows. Set on exactly one seeded role (Everyone);
     * there is no UI to toggle it. Protected against delete/rename/clear. See
     * ADR-0013.
     */
    @Column(name = "auto_all", columnDefinition = "INTEGER")
    public boolean autoAll = false;

    public static Role createNew(String name, String description) {
        Role r = new Role();
        r.id = UUID.randomUUID().toString();
        r.name = name;
        r.description = description;
        r.createdAt = Instant.now();
        return r;
    }
}
