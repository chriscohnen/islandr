package de.chriscohnen.islandr.apikey;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One named capability granted to an {@link ApiKey} (apikey-scopes,
 * loop/TASK.md) — replaces the v1 "every key is full-admin-equivalent"
 * model (ADR-0026, R-184).
 *
 * <p>{@link #siteId} is still always null — no scope in {@link #CATALOG}
 * ended up needing a per-site binding. {@link #edgeHubId} is the object
 * binding that actually got used, for {@code psks:read} (edge-psk-exposure,
 * V87) and {@code telemetry:write} (edge-status-report) — a scope bound to
 * one edge, not one site: an edge serves several sites, so site-level
 * binding would be a level too narrow. {@code rules:read} (edge-plan-endpoint)
 * is deferred — see loop/TASK.md's "Offene Entscheidungen" — but would bind
 * the same way.
 *
 * <p>A surrogate {@code id} rather than a composite primary key — same
 * convention every other entity in this codebase uses (see e.g.
 * {@code PeerSelfShare}), and simpler than making Panache's query helpers
 * work through a JPA {@code @EmbeddedId}.
 */
@Entity
@Table(name = "api_key_scopes")
public class ApiKeyScope extends PanacheEntityBase {

    /** Unrestricted — every other named scope implied. Grandfathered onto
     *  every key that existed before scoping was introduced (V85). */
    public static final String FULL = "full";

    public static final String PEERS_READ = "peers:read";
    public static final String USERS_READ = "users:read";
    public static final String RESOURCES_READ = "resources:read";
    public static final String GRANTS_READ = "grants:read";
    public static final String AUDIT_READ = "audit:read";
    public static final String PEERS_WRITE = "peers:write";
    public static final String USERS_WRITE = "users:write";
    public static final String CONFIG_EXPORT = "config:export";
    public static final String CONFIG_IMPORT = "config:import";
    /** edge-psk-exposure, V87 — always edge-bound (see {@link #EDGE_BOUND_SCOPES});
     *  a key holding it reads the stored PSK ({@code Peer.presharedKey}) of
     *  every peer that may reach that one edge ({@code AclResolutionService
     *  #reachableEdgeHubIdsForPeer}), never any other peer's. Deliberately separate
     *  from {@link #PEERS_READ}: a stored PSK is materially more sensitive
     *  than the peer metadata that scope already exposes. */
    public static final String PSKS_READ = "psks:read";
    /** edge-status-report, V87 — always edge-bound (see
     *  {@link #EDGE_BOUND_SCOPES}); lets a key report connectivity/traffic
     *  for peers that may reach that one edge (POST /api/external/v1/edge/{id}/status),
     *  the same way the central hub's own {@code wg show dump} does for its
     *  interface. A reported public key that may not actually reach that
     *  calling edge is rejected and audited, never silently applied — see
     *  {@code EdgeExternalResource#status}. */
    public static final String TELEMETRY_WRITE = "telemetry:write";

    /** Every scope a key can actually be given. Deliberately excludes
     *  {@code propose} — a materially bigger feature (a pending change an
     *  API key can suggest but not apply, mirroring the ACL matrix's own
     *  "changed, not applied" state) tracked as its own future task, not
     *  part of apikey-scopes. */
    public static final List<String> CATALOG = List.of(
            FULL, PEERS_READ, USERS_READ, RESOURCES_READ, GRANTS_READ, AUDIT_READ,
            PEERS_WRITE, USERS_WRITE, CONFIG_EXPORT, CONFIG_IMPORT, PSKS_READ, TELEMETRY_WRITE);

    /** Scopes that are meaningless without an {@link #edgeHubId} —
     *  {@link ApiKeyService#create} requires one whenever the requested
     *  scope set intersects this. */
    public static final Set<String> EDGE_BOUND_SCOPES = Set.of(PSKS_READ, TELEMETRY_WRITE);

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @Column(name = "api_key_id", nullable = false, length = 36)
    public String apiKeyId;

    @Column(name = "scope", nullable = false, length = 50)
    public String scope;

    /** Always null — see the class doc comment. */
    @Column(name = "site_id", length = 36)
    public String siteId;

    /** Null for every scope outside {@link #EDGE_BOUND_SCOPES}; the one
     *  {@link de.chriscohnen.islandr.acl.EdgeHub} this scope applies to
     *  otherwise. */
    @Column(name = "edge_hub_id", length = 36)
    public String edgeHubId;

    public static ApiKeyScope createNew(String apiKeyId, String scope) {
        return createNew(apiKeyId, scope, null);
    }

    public static ApiKeyScope createNew(String apiKeyId, String scope, String edgeHubId) {
        ApiKeyScope s = new ApiKeyScope();
        s.id = UUID.randomUUID().toString();
        s.apiKeyId = apiKeyId;
        s.scope = scope;
        s.edgeHubId = edgeHubId;
        return s;
    }

    public static List<String> scopesOf(String apiKeyId) {
        return ApiKeyScope.<ApiKeyScope>list("apiKeyId", apiKeyId)
                .stream().map(s -> s.scope).toList();
    }

    public static void deleteFor(String apiKeyId) {
        ApiKeyScope.delete("apiKeyId", apiKeyId);
    }
}
