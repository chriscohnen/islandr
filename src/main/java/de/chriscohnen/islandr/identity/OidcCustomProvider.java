package de.chriscohnen.islandr.identity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per admin-configured generic OIDC provider (Okta, Auth0, Keycloak,
 * or any other spec-compliant issuer) — issue #69, the follow-on to the two
 * hardcoded providers in {@link OidcProvider} (MS365/Google).
 *
 * <p>Deliberately a separate table/entity rather than widening
 * {@link OidcProvider}: MS365/Google keep their hardcoded-endpoint fast path
 * (see {@link ProviderEndpoints}) completely untouched. A custom provider's
 * endpoints are instead resolved once via OIDC discovery
 * ({@code {issuerUrl}/.well-known/openid-configuration}) at config-save time
 * and cached in this row — never re-fetched on the login hot path, same
 * "no extra dependency at login time" reasoning {@link ProviderEndpoints}
 * already documents.
 *
 * <p>{@link #preset} is purely a UI hint (which tile/logo/input-shape the
 * admin used to set this up — a plain domain for Auth0/Okta vs. a pasted
 * issuer URL for the fully generic "Custom OIDC provider" tile). It plays no
 * role in login/verification, which always uses the discovered endpoints
 * uniformly regardless of preset.
 *
 * <p>Stored secrets ({@link #clientSecret}) are intentionally not encrypted
 * at-rest — same trust-boundary rationale as {@link OidcProvider}.
 */
@Entity
@Table(name = "oidc_custom_providers")
public class OidcCustomProvider extends PanacheEntityBase {

    public static final String PRESET_AUTH0 = "auth0";
    public static final String PRESET_OKTA = "okta";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    /** {@code "auth0"} | {@code "okta"} | {@code null} (fully generic). */
    @Column(name = "preset", length = 16)
    public String preset;

    @Column(name = "display_name", nullable = false)
    public String displayName;

    @Column(name = "issuer_url", nullable = false, length = 512)
    public String issuerUrl;

    /** Populated by discovery; null until the first successful discovery run. */
    @Column(name = "authorize_endpoint", length = 512)
    public String authorizeEndpoint;

    @Column(name = "token_endpoint", length = 512)
    public String tokenEndpoint;

    @Column(name = "jwks_uri", length = 512)
    public String jwksUri;

    @Column(name = "userinfo_endpoint", length = 512)
    public String userinfoEndpoint;

    /** The {@code issuer} claim discovery actually returned — compared against
     *  ID-Token {@code iss} at verification time, same as MS365/Google. */
    @Column(name = "discovered_issuer", length = 512)
    public String discoveredIssuer;

    @Column(name = "discovered_at")
    public Instant discoveredAt;

    @Column(name = "client_id", length = 255)
    public String clientId;

    @Column(name = "client_secret", length = 512)
    public String clientSecret;

    @Column(name = "scopes", nullable = false)
    public String scopes = "openid profile email";

    /** CSV of email domains permitted to auto-provision. Empty means "no one auto-provisions". */
    @Column(name = "allowed_domains", columnDefinition = "TEXT")
    public String allowedDomains;

    @Column(name = "enabled", nullable = false)
    public boolean enabled;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 255)
    public String updatedBy;

    public boolean isDiscovered() {
        return authorizeEndpoint != null && tokenEndpoint != null && jwksUri != null;
    }
}
