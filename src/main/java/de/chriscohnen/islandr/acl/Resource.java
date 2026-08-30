package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import de.chriscohnen.islandr.validation.ValidIpAddress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

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

    /** Optional DNS label for the resource-name resolver (ADR-0023, MVP —
     *  admin-typed, no automatic discovery yet). Lowercase DNS label syntax
     *  (letters/digits/hyphens, not starting or ending with a hyphen), matched
     *  against the standalone-label RFC 1035 rule. Null = never resolves. */
    @Pattern(regexp = "^$|^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$",
            message = "must be a lowercase DNS label (letters, digits, hyphens; not starting/ending with a hyphen)")
    @Column(name = "dns_name", length = 63)
    public String dnsName;

    /** When true, this resource resolves as {@code <dnsName>.<zone>} directly —
     *  no site subdomain layer — instead of {@code <dnsName>.<site>.<zone>}
     *  (ADR-0023 follow-up). Meaningless unless {@code dnsName} is also set.
     *  Uniqueness for a flat name is checked globally (no site label left to
     *  disambiguate it), unlike the per-site check for non-flat names. */
    @Column(name = "dns_flat", nullable = false, columnDefinition = "INTEGER")
    public boolean dnsFlat = false;

    /** Exclusive-capacity limit (issue #72): how many users may hold this
     *  resource at the same time. {@code null} — the default, and every
     *  resource that existed before #72 — means unlimited, i.e. a grant alone
     *  is enough to reach it, exactly as before. Any non-null value opts the
     *  resource into the reservation layer: a standing grant then establishes
     *  only *eligibility*, and an active {@link ResourceReservation} is
     *  additionally required to actually reach it. That is what stops the
     *  shared-function-account case (an RDP box with one session) from being
     *  circumvented by simply handing three people a role grant. */
    @Column(name = "max_concurrent_users")
    public Integer maxConcurrentUsers;

    /** Ceiling on a single self-service reservation's length, in minutes.
     *  {@code null} = no ceiling beyond the duration picker's own options.
     *  Independent of {@link #maxConcurrentUsers} in the schema, but only
     *  meaningful once the resource is capacity-limited. */
    @Column(name = "max_reservation_minutes")
    public Integer maxReservationMinutes;

    /** When true (default), a request that fits the remaining capacity is
     *  granted immediately; when false, every request waits for an admin
     *  decision even on an idle resource. */
    @Column(name = "auto_approve_reservations", nullable = false, columnDefinition = "INTEGER")
    public boolean autoApproveReservations = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** True when this resource takes part in the reservation layer at all. */
    public boolean isCapacityLimited() {
        return maxConcurrentUsers != null;
    }

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
