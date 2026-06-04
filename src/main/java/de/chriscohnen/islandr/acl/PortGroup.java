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
 * A reusable template of {@code (port, transport, protocol, label)} tuples.
 * Applying a group to a {@link Resource} copies its members into
 * {@code resource_ports} — there is no live link, so editing the group
 * later does not mutate already-configured resources.
 */
@Entity
@Table(name = "port_groups")
public class PortGroup extends PanacheEntityBase {

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

    public static PortGroup createNew(String name, String description) {
        PortGroup g = new PortGroup();
        g.id = UUID.randomUUID().toString();
        g.name = name;
        g.description = description;
        g.createdAt = Instant.now();
        return g;
    }
}
