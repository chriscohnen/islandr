package de.chriscohnen.islandr.apikey;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;
import java.util.UUID;

/**
 * One named capability granted to an {@link ApiKey} (apikey-scopes,
 * loop/TASK.md) — replaces the v1 "every key is full-admin-equivalent"
 * model (ADR-0026, R-184).
 *
 * <p>{@link #siteId} is always null in 1.1.0: no scope in {@link #CATALOG}
 * needs a per-site binding yet. It exists now so the two scopes deferred to
 * 1.2.0 ({@code rules:read}, {@code telemetry:write} — see loop/TASK.md's
 * "Offene Entscheidungen") can be bound to a single site without a second
 * migration; an unbound scope (site-less) applies everywhere.
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

    /** Every scope a key can actually be given in 1.1.0. Deliberately
     *  excludes {@code rules:read}/{@code telemetry:write} — see this
     *  class's own doc comment — and {@code propose}, a materially bigger
     *  feature (a pending change an API key can suggest but not apply,
     *  mirroring the ACL matrix's own "changed, not applied" state) tracked
     *  as its own future task, not part of apikey-scopes. */
    public static final List<String> CATALOG = List.of(
            FULL, PEERS_READ, USERS_READ, RESOURCES_READ, GRANTS_READ, AUDIT_READ,
            PEERS_WRITE, USERS_WRITE, CONFIG_EXPORT, CONFIG_IMPORT);

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    @Column(name = "api_key_id", nullable = false, length = 36)
    public String apiKeyId;

    @Column(name = "scope", nullable = false, length = 50)
    public String scope;

    /** Always null in 1.1.0 — see the class doc comment. */
    @Column(name = "site_id", length = 36)
    public String siteId;

    public static ApiKeyScope createNew(String apiKeyId, String scope) {
        ApiKeyScope s = new ApiKeyScope();
        s.id = UUID.randomUUID().toString();
        s.apiKeyId = apiKeyId;
        s.scope = scope;
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
