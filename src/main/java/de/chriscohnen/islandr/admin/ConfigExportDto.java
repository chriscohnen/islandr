package de.chriscohnen.islandr.admin;

import java.time.Instant;
import java.util.List;

public class ConfigExportDto {

    public record Export(
        String version,
        Instant exportedAt,
        boolean privateKeysIncluded,
        SettingsSnapshot settings,
        List<OidcProviderSnapshot> oidcProviders,
        List<UserSnapshot> users,
        List<RoleSnapshot> roles,
        List<RoleMembership> roleMemberships,
        List<PeerSnapshot> peers,
        List<SiteSnapshot> sites,
        List<ResourceSnapshot> resources,
        List<ResourcePortSnapshot> resourcePorts,
        List<PortGroupSnapshot> portGroups,
        List<PortGroupMemberSnapshot> portGroupMembers,
        List<GrantSnapshot> roleResourceGrants,
        List<GrantPortLink> grantPortLinks,
        // Nullable: an export from before this field existed imports as if
        // this list were empty — same "add-a-field, tolerate its absence"
        // pattern as wgMtu above.
        List<TypeGrantSnapshot> roleResourceTypeGrants,
        // Same tolerate-absence pattern, for direct user-grants (ADR-0024).
        List<UserGrantSnapshot> userResourceGrants,
        List<UserGrantPortLink> userGrantPortLinks,
        // Same tolerate-absence pattern, for direct site-grants.
        List<SiteGrantSnapshot> siteResourceGrants,
        List<SiteGrantPortLink> siteGrantPortLinks,
        // Same tolerate-absence pattern, for the Peer-Scheduler (#47/Z.58).
        // Peer.validUntil/enabledSource travel on PeerSnapshot itself.
        List<PeerScheduleSnapshot> peerSchedules,
        // Same tolerate-absence pattern, for admin-configured generic OIDC
        // providers (Auth0/Okta/Keycloak/any issuer — issue #69). Previously
        // only the two hardcoded MS365/Google rows (oidcProviders above)
        // travelled with an export; a custom provider silently vanished on
        // restore, locking out every user who logged in through it.
        List<OidcCustomProviderSnapshot> oidcCustomProviders,
        // Same tolerate-absence pattern, for external-API keys (ADR-0026).
        // Only the SHA-256 hash is ever stored (ApiKey's own contract) — a
        // *newly revealed* raw key genuinely cannot be reconstructed, but
        // that has no bearing on whether the hash rows themselves travel:
        // an already-issued key's holder still has the raw value and can
        // keep authenticating with it after a restore, because verification
        // only ever needs the hash, never the raw key. Previously the whole
        // list was dropped, silently revoking every key on a config restore.
        List<ApiKeySnapshot> apiKeys
    ) {}

    public record SettingsSnapshot(
        String wgSubnet,
        String wgServerPublicKey,
        String wgServerEndpoint,
        String wgClientAllowedIps,
        String wgClientDns,
        String privateKeyRetention,
        boolean gravatarEnabled,
        boolean oidcAutoProvision,
        boolean firewallDryRun,
        boolean selfServicePeerCreation,
        Integer wgMtu,
        boolean wgIncludeMtuInConf,
        // Integer (not int) so a pre-0.13.0 export without this field imports as
        // null → default 25, instead of primitive 0 (which would disable keepalive).
        Integer wgPersistentKeepalive,
        // Added for #33 (ADR-0017). A pre-existing export (created before this feature
        // existed) has these as null on import; the import code below must default them
        // the same way Settings's own entity defaults do (SPLIT/MANUAL/null) rather than
        // crash or silently force a specific mode.
        String tunnelMode,
        String allowedIpsMode,
        String splitSupernet,
        // Optional IPv6 ULA subnet (Settings.wgSubnet6) — null on IPv4-only installs and
        // on any export from before this field existed.
        String wgSubnet6,
        // Hub location for the dashboard topology map (Settings.hubLat/hubLon/hubLocationLabel).
        // Nullable: unset on installs that never filled in the map location, and on any
        // export from before these fields existed.
        Double hubLat,
        Double hubLon,
        String hubLocationLabel,
        // Optional Nominatim base URL for Sites-view geocoding (Settings.nominatimUrl).
        String nominatimUrl,
        // Boolean (not primitive) so a pre-existing export without this field imports as
        // null → the entity default (false), same "add-a-field, tolerate its absence"
        // pattern as wgIncludeMtuInConf above.
        Boolean ironRdpEnabled,
        // Integer (not int) so a pre-existing export without this field imports as
        // null → the entity default (180), instead of primitive 0 (which would prune
        // the activity heatmap immediately).
        Integer activityRetentionDays,
        // Opt-in for the resource-name DNS resolver (ADR-0023) — persisted intent
        // only, see Settings.java. Boolean (not primitive) so a pre-existing export
        // without this field imports as null → the entity default (false), same
        // "add-a-field, tolerate its absence" pattern as ironRdpEnabled above.
        Boolean dnsResolverEnabled,
        String dnsResolverZone,
        // Where the resolver forwards non-zone queries — independent of
        // wgClientDns, see Settings.java. Null on pre-existing exports.
        String dnsResolverUpstream,
        // Opt-out for the external automation API facade (ADR-0026). Boolean (not
        // primitive) so a pre-existing export without this field imports as null →
        // the entity default (true, facade enabled), same "add-a-field, tolerate
        // its absence" pattern as ironRdpEnabled/dnsResolverEnabled above.
        Boolean externalApiEnabled
    ) {}

    public record OidcProviderSnapshot(
        String providerKey,
        boolean enabled,
        String clientId,
        String clientSecret,
        String tenantId,
        String allowedDomains
    ) {}

    /** One admin-configured generic OIDC provider (Okta/Auth0/Keycloak/any
     *  issuer — issue #69, {@link de.chriscohnen.islandr.identity.OidcCustomProvider}).
     *  Unlike the two hardcoded providers above, these are ordinary
     *  admin-created rows with arbitrary ids — imported the same
     *  delete-then-reinsert way as sites/resources/etc., not updated
     *  in-place. Endpoints/discoveredIssuer/discoveredAt travel too so a
     *  restored instance doesn't need network access to a (possibly
     *  temporarily unreachable) IdP just to log in again immediately. */
    public record OidcCustomProviderSnapshot(
        String id,
        String preset,
        String displayName,
        String issuerUrl,
        String authorizeEndpoint,
        String tokenEndpoint,
        String jwksUri,
        String userinfoEndpoint,
        String discoveredIssuer,
        Instant discoveredAt,
        String clientId,
        String clientSecret,
        String scopes,
        String allowedDomains,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy
    ) {}

    /** One admin-issued external-API key (issue #15, ADR-0026). Only the
     *  SHA-256 hash + non-secret prefix travel, same as at rest — see
     *  {@link Export#apiKeys()} for why that's still useful, not a "dead
     *  row": an already-issued key keeps authenticating after a restore,
     *  since verification only ever needs the hash, never the raw value. */
    public record ApiKeySnapshot(
        String id,
        String label,
        String keyHash,
        String keyPrefix,
        Instant createdAt,
        String createdBy,
        Instant lastUsedAt,
        Instant revokedAt
    ) {}

    public record UserSnapshot(
        String id,
        String name,
        String email,
        String nickname,
        boolean enabled,
        boolean isAdmin,
        String oidcProvider,
        String oidcSubject,
        String preferredLocale,
        Instant createdAt,
        // Which OidcCustomProviderSnapshot (by id) this user last authenticated
        // through, when oidcProvider="custom" — null otherwise, and null on any
        // export from before this field existed. Without it, a user who signed
        // in via a custom provider couldn't be matched back to that provider's
        // row on next login after a restore (see User.findByOidc's 3-way match
        // on provider+subject+customProviderId).
        String oidcCustomProviderId
    ) {}

    /**
     * {@code autoAll} marks the auto-membership "Everyone" role (ADR-0013). It has to travel
     * with the snapshot: without it the import re-creates the role with the flag cleared, and
     * the next boot finds no auto-membership role, tries to seed a fresh "Everyone", and dies
     * on the unique index over {@code roles.name} — the instance never starts again.
     */
    public record RoleSnapshot(
        String id,
        String name,
        String description,
        boolean autoAll,
        Instant createdAt
    ) {}

    public record RoleMembership(String userId, String roleId) {}

    public record PeerSnapshot(
        String id,
        String userId,
        String name,
        String publicKey,
        String assignedIp,
        boolean enabled,
        String privateKeyPem,
        String type,
        String siteAllowedCidrs,
        String deviceType,
        String presharedKey,
        Instant createdAt,
        // Geocoding — meaningful for type="site" only (physical gateway device location).
        Double lat,
        Double lng,
        String locationLabel,
        // Peer-Scheduler (#47/Z.58): validUntil is a one-off expiry (e.g. an ad-hoc
        // enable), enabledSource records who/what last flipped `enabled` ("admin",
        // "schedule", "expiry") so a restored peer's audit trail stays legible.
        // Both null on any export from before the Peer-Scheduler existed.
        Instant validUntil,
        String enabledSource
    ) {}

    public record SiteSnapshot(
        String id,
        String name,
        String cidr,
        String description,
        String gatewayPeerId,
        Instant createdAt
    ) {}

    public record ResourceSnapshot(
        String id,
        String siteId,
        String name,
        String ip,
        String description,
        String type,
        Instant createdAt
    ) {}

    public record ResourcePortSnapshot(
        String id,
        String resourceId,
        int port,
        Integer portEnd,
        String transport,
        String protocol,
        String label,
        // Exclusive-capacity config (issue #72). Boxed so an export written
        // before #72 imports cleanly: absent means "no capacity limit", which
        // is exactly the pre-#72 behaviour. autoApproveReservations is boxed
        // for the same reason and falls back to the entity default (true) when
        // the key is missing, rather than silently importing as false.
        //
        // The reservations themselves are deliberately NOT exported — same
        // reasoning as #70's ad-hoc temporary grants: they carry an expiry,
        // they are transient by design, and a restore should not resurrect
        // somebody's half-finished session from whenever the backup was taken.
        Integer maxConcurrentUsers,
        Integer maxReservationMinutes,
        Boolean autoApproveReservations,
        Instant createdAt
    ) {}

    public record PortGroupSnapshot(
        String id,
        String name,
        String description,
        Instant createdAt
    ) {}

    public record PortGroupMemberSnapshot(
        String id,
        String portGroupId,
        int port,
        Integer portEnd,
        String transport,
        String protocol,
        String label
    ) {}

    public record GrantSnapshot(
        String id,
        String roleId,
        String resourceId,
        boolean allPorts,
        Instant createdAt
    ) {}

    public record GrantPortLink(String grantId, String portId) {}

    public record TypeGrantSnapshot(
        String id,
        String roleId,
        String siteId,
        String resourceType,
        Instant createdAt
    ) {}

    public record UserGrantSnapshot(
        String id,
        String userId,
        String resourceId,
        boolean allPorts,
        Instant createdAt
    ) {}

    public record UserGrantPortLink(String grantId, String portId) {}

    public record SiteGrantSnapshot(
        String id,
        String siteId,
        String resourceId,
        boolean allPorts,
        Instant createdAt
    ) {}

    public record SiteGrantPortLink(String grantId, String portId) {}

    public record PeerScheduleSnapshot(
        String id,
        String peerId,
        int weekdayMask,
        String activeFrom,
        String activeTo,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record ImportResult(
        int users,
        int roles,
        int peers,
        int sites,
        int resources,
        int portGroups,
        int grants
    ) {}
}
