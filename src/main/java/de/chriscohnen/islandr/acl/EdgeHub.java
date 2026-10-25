package de.chriscohnen.islandr.acl;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A second WireGuard hub at a remote site (edge-hub-entity, loop/TASK.md,
 * ADR-I-0002). Modelled as its own entity rather than a flag on {@link Site}
 * for the same reason {@link Site} itself has no coordinates of its own
 * (see that class's javadoc): an edge hub can serve more than one site, and
 * a site has no physical location.
 *
 * <p>The central hub has no row here — it stays implicit, with its own
 * coordinates on {@code settings.hub_lat}/{@code hub_lon} (see
 * loop/TASK.md, "Offene Entscheidungen" — "Bleibt der Zentral-Hub in
 * settings?", decided 2026-09-29). A {@link Site} whose {@link #id}
 * (via {@code Site.edgeHubId}) is null is served by the central hub.
 *
 * <p>{@link #endpoint} and {@link #serverPublicKey} describe this edge the way
 * {@code settings.wg_server_endpoint}/{@code wg_server_public_key} describe
 * the central hub — what clients dial, and whose key they trust. Both are
 * optional here (an edge is usually created before the machine behind it
 * exists) and required at the point they are first needed: assigning a peer to
 * an edge that has neither is refused, because that peer's configuration could
 * not be rendered (ADR-I-0003).
 *
 * <p>{@link #apiKeyId}, {@link #lastSyncAt}, {@link #lastPlanSerial} and
 * {@link #lastAppliedAt} are unused this slice — they back the
 * config-distribution work deferred to a later cycle
 * ({@code edge-plan-endpoint}, {@code edge-status-report}), added now so that
 * work doesn't need a second migration (V86).
 *
 * <p>There is deliberately no {@code peerId} (dropped in V89): an edge
 * authenticates to the hub as an API client, not as a WireGuard peer of it.
 * Its own tunnel to the hub, if an operator wants one at all, is something
 * they build by hand and Islandr knows nothing about.
 */
@Entity
@Table(name = "edge_hubs")
public class EdgeHub extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @NotBlank
    @Column(name = "name", nullable = false, unique = true)
    public String name;

    @Column(name = "enabled", nullable = false)
    public boolean enabled;

    /** Manually entered, same convention as {@code Settings.hubLat}/{@code hubLon}
     *  and {@link de.chriscohnen.islandr.peer.Peer#lat} — no GeoIP (ADR-0021). */
    @NotNull
    @Column(name = "lat", nullable = false)
    public Double lat;

    @NotNull
    @Column(name = "lng", nullable = false)
    public Double lng;

    @Column(name = "location_label")
    public String locationLabel;

    /** What a client at this edge puts in its {@code Endpoint} line —
     *  {@code host:port}, same shape as {@code settings.wgServerEndpoint}.
     *  null until the edge's WireGuard side is set up. */
    @Column(name = "endpoint", length = 255)
    public String endpoint;

    /** The edge's own WireGuard server public key, which a client at this edge
     *  puts in its {@code [Peer] PublicKey} line. null until set up. */
    @Column(name = "server_public_key", length = 44)
    public String serverPublicKey;

    /** Which resolver a peer at this edge should use (edge-peer-dns). null =
     *  write no {@code DNS} line at all for those peers, so the client keeps
     *  its own resolver; resources stay reachable by address, the managed names
     *  do not resolve. Deliberately not inherited from the central hub's
     *  resolver: that address is only reachable from here if an operator built
     *  a tunnel between the hubs and routed it, which Islandr knows nothing
     *  about (see class javadoc). A resolver running at the edge itself is the
     *  other reason to fill this in. */
    @Column(name = "dns_server_ip", length = 45)
    public String dnsServerIp;

    /** True once this edge can actually serve a peer: a client needs both
     *  values to build a working tunnel, and neither is useful alone. */
    public boolean canServePeers() {
        return endpoint != null && !endpoint.isBlank()
                && serverPublicKey != null && !serverPublicKey.isBlank();
    }

    /** Unused this slice — see class javadoc. */
    @Column(name = "api_key_id", length = 36)
    public String apiKeyId;

    /** Unused this slice — see class javadoc. */
    @Column(name = "last_sync_at")
    public Instant lastSyncAt;

    /** Unused this slice — see class javadoc. */
    @Column(name = "last_plan_serial")
    public Integer lastPlanSerial;

    /** Unused this slice — see class javadoc. */
    @Column(name = "last_applied_at")
    public Instant lastAppliedAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public static EdgeHub createNew(String name, double lat, double lng, String locationLabel) {
        EdgeHub e = new EdgeHub();
        e.id = UUID.randomUUID().toString();
        e.name = name;
        e.enabled = true;
        e.lat = lat;
        e.lng = lng;
        e.locationLabel = locationLabel;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }
}
