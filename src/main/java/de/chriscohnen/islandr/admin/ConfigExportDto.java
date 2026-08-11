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
        List<UserGrantPortLink> userGrantPortLinks
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
        String dnsResolverUpstream
    ) {}

    public record OidcProviderSnapshot(
        String providerKey,
        boolean enabled,
        String clientId,
        String clientSecret,
        String tenantId,
        String allowedDomains
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
        Instant createdAt
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
        String locationLabel
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
