package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import de.chriscohnen.islandr.validation.ValidIpAddress;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

/**
 * A named host inside a {@link Site}, identified by IP. The (site, ip) pair
 * is unique so an admin can't accidentally register the same target under
 * two names within the same network. Across sites the same IP is allowed
 * (private ranges reused across remote LANs is common).
 */
@Entity
@Table(name = "resources")
public class Resource extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @NotBlank
    @Column(name = "site_id", nullable = false, length = 36)
    public String siteId;

    @NotBlank
    @Column(name = "name", nullable = false)
    public String name;

    @NotBlank
    @ValidIpAddress
    @Column(name = "ip", nullable = false, length = 45)
    public String ip;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    /** UI-metadata. Allowed: computer | printer | nas | switch. See V12 migration. */
    @NotBlank
    @Column(name = "type", nullable = false, length = 16)
    public String type;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static Resource createNew(String siteId, String name, String ip, String description, String type) {
        Resource r = new Resource();
        r.id = UUID.randomUUID().toString();
        r.siteId = siteId;
        r.name = name;
        r.ip = ip;
        r.description = description;
        r.type = type == null || type.isBlank() ? "computer" : type;
        r.createdAt = Instant.now();
        return r;
    }
}
