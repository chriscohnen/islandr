package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

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
    @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2}$",
            message = "must be IPv4 CIDR (e.g. 10.20.0.0/16)")
    @Column(name = "cidr", nullable = false, length = 50)
    public String cidr;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    @Column(name = "lat")
    public Double lat;

    @Column(name = "lng")
    public Double lng;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static Site createNew(String name, String cidr, String description, Double lat, Double lng) {
        Site s = new Site();
        s.id = UUID.randomUUID().toString();
        s.name = name;
        s.cidr = cidr;
        s.description = description;
        s.lat = lat;
        s.lng = lng;
        s.createdAt = Instant.now();
        return s;
    }
}
