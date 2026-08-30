package de.chriscohnen.islandr.admin;

import de.chriscohnen.islandr.acl.*;
import de.chriscohnen.islandr.apikey.ApiKey;
import de.chriscohnen.islandr.identity.OidcCustomProvider;
import de.chriscohnen.islandr.identity.OidcProvider;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.peer.PeerSchedule;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@ApplicationScoped
public class ConfigService {

    @Inject
    EntityManager em;

    public ConfigExportDto.Export export(boolean includePrivateKeys) {
        Settings s = Settings.findById(Settings.SINGLETON_ID);

        var settings = new ConfigExportDto.SettingsSnapshot(
                s.wgSubnet, s.wgServerPublicKey, s.wgServerEndpoint,
                s.wgClientAllowedIps, s.wgClientDns, s.privateKeyRetention,
                s.gravatarEnabled, s.oidcAutoProvision, s.firewallDryRun,
                s.selfServicePeerCreation, s.wgMtu, s.wgIncludeMtuInConf,
                s.wgPersistentKeepalive,
                s.tunnelMode, s.allowedIpsMode, s.splitSupernet,
                s.wgSubnet6,
                s.hubLat, s.hubLon, s.hubLocationLabel,
                s.nominatimUrl, s.ironRdpEnabled, s.activityRetentionDays,
                s.dnsResolverEnabled, s.dnsResolverZone, s.dnsResolverUpstream,
                s.externalApiEnabled);

        List<ConfigExportDto.OidcProviderSnapshot> providers = OidcProvider.<OidcProvider>listAll()
                .stream().map(p -> new ConfigExportDto.OidcProviderSnapshot(
                        p.providerKey, p.enabled, p.clientId, p.clientSecret,
                        p.tenantId, p.allowedDomains))
                .toList();

        List<ConfigExportDto.UserSnapshot> users = User.<User>listAll()
                .stream().map(u -> new ConfigExportDto.UserSnapshot(
                        u.id, u.name, u.email, u.nickname, u.enabled, u.isAdmin,
                        u.oidcProvider, u.oidcSubject, u.preferredLocale, u.createdAt,
                        u.oidcCustomProviderId))
                .toList();

        List<ConfigExportDto.OidcCustomProviderSnapshot> customProviders =
                OidcCustomProvider.<OidcCustomProvider>listAll()
                .stream().map(p -> new ConfigExportDto.OidcCustomProviderSnapshot(
                        p.id, p.preset, p.displayName, p.issuerUrl,
                        p.authorizeEndpoint, p.tokenEndpoint, p.jwksUri, p.userinfoEndpoint,
                        p.discoveredIssuer, p.discoveredAt,
                        p.clientId, p.clientSecret, p.scopes, p.allowedDomains,
                        p.enabled, p.createdAt, p.updatedAt, p.updatedBy))
                .toList();

        List<ConfigExportDto.ApiKeySnapshot> apiKeys = ApiKey.<ApiKey>listAll()
                .stream().map(k -> new ConfigExportDto.ApiKeySnapshot(
                        k.id, k.label, k.keyHash, k.keyPrefix,
                        k.createdAt, k.createdBy, k.lastUsedAt, k.revokedAt))
                .toList();

        List<ConfigExportDto.RoleSnapshot> roles = Role.<Role>listAll()
                .stream().map(r -> new ConfigExportDto.RoleSnapshot(
                        r.id, r.name, r.description, r.autoAll, r.createdAt))
                .toList();

        @SuppressWarnings("unchecked")
        List<Object[]> memberRows = em.createNativeQuery(
                        "SELECT user_id, role_id FROM user_roles")
                .getResultList();
        List<ConfigExportDto.RoleMembership> memberships = memberRows.stream()
                .map(r -> new ConfigExportDto.RoleMembership((String) r[0], (String) r[1]))
                .toList();

        List<ConfigExportDto.PeerSnapshot> peers = Peer.<Peer>listAll()
                .stream().map(p -> new ConfigExportDto.PeerSnapshot(
                        p.id, p.userId, p.name, p.publicKey, p.assignedIp, p.enabled,
                        includePrivateKeys ? p.privateKeyPem : null,
                        p.type, p.siteAllowedCidrs, p.deviceType, p.presharedKey, p.createdAt,
                        p.lat, p.lng, p.locationLabel, p.validUntil, p.enabledSource))
                .toList();

        List<ConfigExportDto.PeerScheduleSnapshot> peerSchedules = PeerSchedule.<PeerSchedule>listAll()
                .stream().map(ps -> new ConfigExportDto.PeerScheduleSnapshot(
                        ps.id, ps.peerId, ps.weekdayMask, ps.activeFrom, ps.activeTo,
                        ps.createdAt, ps.updatedAt))
                .toList();

        List<ConfigExportDto.SiteSnapshot> sites = Site.<Site>listAll()
                .stream().map(site -> new ConfigExportDto.SiteSnapshot(
                        site.id, site.name, site.cidr, site.description,
                        site.gatewayPeerId, site.createdAt))
                .toList();

        List<ConfigExportDto.ResourceSnapshot> resources = Resource.<Resource>listAll()
                .stream().map(r -> new ConfigExportDto.ResourceSnapshot(
                        r.id, r.siteId, r.name, r.ip, r.description, r.type,
                        r.maxConcurrentUsers, r.maxReservationMinutes, r.autoApproveReservations,
                        r.createdAt))
                .toList();

        List<ConfigExportDto.ResourcePortSnapshot> ports = ResourcePort.<ResourcePort>listAll()
                .stream().map(p -> new ConfigExportDto.ResourcePortSnapshot(
                        p.id, p.resourceId, p.port, p.portEnd,
                        p.transport, p.protocol, p.label, p.createdAt))
                .toList();

        List<ConfigExportDto.PortGroupSnapshot> portGroups = PortGroup.<PortGroup>listAll()
                .stream().map(g -> new ConfigExportDto.PortGroupSnapshot(
                        g.id, g.name, g.description, g.createdAt))
                .toList();

        List<ConfigExportDto.PortGroupMemberSnapshot> portGroupMembers = PortGroupMember.<PortGroupMember>listAll()
                .stream().map(m -> new ConfigExportDto.PortGroupMemberSnapshot(
                        m.id, m.portGroupId, m.port, m.portEnd,
                        m.transport, m.protocol, m.label))
                .toList();

        List<ConfigExportDto.GrantSnapshot> grants = RoleResourceGrant.<RoleResourceGrant>listAll()
                .stream().map(g -> new ConfigExportDto.GrantSnapshot(
                        g.id, g.roleId, g.resourceId, g.allPorts, g.createdAt))
                .toList();

        @SuppressWarnings("unchecked")
        List<Object[]> grantPortRows = em.createNativeQuery(
                        "SELECT grant_id, port_id FROM role_resource_grant_ports")
                .getResultList();
        List<ConfigExportDto.GrantPortLink> grantPortLinks = grantPortRows.stream()
                .map(r -> new ConfigExportDto.GrantPortLink((String) r[0], (String) r[1]))
                .toList();

        List<ConfigExportDto.TypeGrantSnapshot> typeGrants = RoleResourceTypeGrant.<RoleResourceTypeGrant>listAll()
                .stream().map(g -> new ConfigExportDto.TypeGrantSnapshot(
                        g.id, g.roleId, g.siteId, g.resourceType, g.createdAt))
                .toList();

        // Ad-hoc temporary grants (#70) are deliberately excluded from a config
        // snapshot: they carry a validUntil and are meant to expire on their own via
        // UserGrantExpiryJob. Restoring a backup should never resurrect a grant that
        // was only ever meant to be temporary — and may already be stale/expired by
        // the time the backup is imported. Only permanent (validUntil == null) direct
        // user-grants travel with the export.
        List<UserResourceGrant> permanentUserGrants = UserResourceGrant.<UserResourceGrant>list("validUntil is null");
        List<ConfigExportDto.UserGrantSnapshot> userGrants = permanentUserGrants.stream()
                .map(g -> new ConfigExportDto.UserGrantSnapshot(
                        g.id, g.userId, g.resourceId, g.allPorts, g.createdAt))
                .toList();

        java.util.Set<String> permanentGrantIds = permanentUserGrants.stream()
                .map(g -> g.id).collect(java.util.stream.Collectors.toSet());
        @SuppressWarnings("unchecked")
        List<Object[]> userGrantPortRows = em.createNativeQuery(
                        "SELECT grant_id, port_id FROM user_resource_grant_ports")
                .getResultList();
        List<ConfigExportDto.UserGrantPortLink> userGrantPortLinks = userGrantPortRows.stream()
                .filter(r -> permanentGrantIds.contains((String) r[0]))
                .map(r -> new ConfigExportDto.UserGrantPortLink((String) r[0], (String) r[1]))
                .toList();

        List<ConfigExportDto.SiteGrantSnapshot> siteGrants = SiteResourceGrant.<SiteResourceGrant>listAll()
                .stream().map(g -> new ConfigExportDto.SiteGrantSnapshot(
                        g.id, g.siteId, g.resourceId, g.allPorts, g.createdAt))
                .toList();

        @SuppressWarnings("unchecked")
        List<Object[]> siteGrantPortRows = em.createNativeQuery(
                        "SELECT grant_id, port_id FROM site_resource_grant_ports")
                .getResultList();
        List<ConfigExportDto.SiteGrantPortLink> siteGrantPortLinks = siteGrantPortRows.stream()
                .map(r -> new ConfigExportDto.SiteGrantPortLink((String) r[0], (String) r[1]))
                .toList();

        return new ConfigExportDto.Export(
                "1", Instant.now(), includePrivateKeys,
                settings, providers, users, roles, memberships, peers,
                sites, resources, ports, portGroups, portGroupMembers,
                grants, grantPortLinks, typeGrants, userGrants, userGrantPortLinks,
                siteGrants, siteGrantPortLinks, peerSchedules,
                customProviders, apiKeys);
    }

    @Transactional
    public ConfigExportDto.ImportResult importConfig(ConfigExportDto.Export p) {
        // --- Tear-down in FK order -------------------------------------------
        // Reservations (issue #72) FK both resources and users, so they go
        // first. They are never re-inserted — reservations are transient
        // session state, deliberately outside the export (see
        // ConfigExportDto.ResourceSnapshot) — a restore starts with nobody
        // holding anything, which is the honest state after a rebuild.
        em.createNativeQuery("DELETE FROM resource_reservations").executeUpdate();
        em.createNativeQuery("DELETE FROM user_resource_grant_ports").executeUpdate();
        em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
        em.createNativeQuery("DELETE FROM role_resource_grants").executeUpdate();
        em.createNativeQuery("DELETE FROM role_resource_type_grants").executeUpdate();
        em.createNativeQuery("DELETE FROM user_resource_grants").executeUpdate();
        em.createNativeQuery("DELETE FROM site_resource_grant_ports").executeUpdate();
        em.createNativeQuery("DELETE FROM site_resource_grants").executeUpdate();
        em.createNativeQuery("DELETE FROM resource_ports").executeUpdate();
        em.createNativeQuery("DELETE FROM resources").executeUpdate();
        // Break the sites ↔ peers FK cycle before deleting either table.
        em.createNativeQuery("UPDATE sites SET gateway_peer_id = NULL").executeUpdate();
        em.createNativeQuery("DELETE FROM sites").executeUpdate();
        em.createNativeQuery("DELETE FROM port_group_members").executeUpdate();
        em.createNativeQuery("DELETE FROM port_groups").executeUpdate();
        em.createNativeQuery("DELETE FROM peer_schedules").executeUpdate();
        em.createNativeQuery("DELETE FROM peers").executeUpdate();
        em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
        // users.oidc_custom_provider_id references oidc_custom_providers (ON
        // DELETE SET NULL) — delete the child (users) before the parent so
        // this teardown never depends on the FK's own cascade behavior.
        em.createNativeQuery("DELETE FROM users").executeUpdate();
        em.createNativeQuery("DELETE FROM oidc_custom_providers").executeUpdate();
        em.createNativeQuery("DELETE FROM roles").executeUpdate();
        em.createNativeQuery("DELETE FROM api_keys").executeUpdate();

        // --- Custom OIDC providers (issue #69) --------------------------------
        // Must precede Users below: users.oidc_custom_provider_id is a
        // (foreign_keys=ON, immediately-checked) FK into this table, so the
        // referenced row has to exist before the referencing user row is
        // inserted.
        for (var op : safe(p.oidcCustomProviders())) {
            em.createNativeQuery(
                            "INSERT INTO oidc_custom_providers (id, preset, display_name, issuer_url," +
                            " authorize_endpoint, token_endpoint, jwks_uri, userinfo_endpoint," +
                            " discovered_issuer, discovered_at, client_id, client_secret, scopes," +
                            " allowed_domains, enabled, created_at, updated_at, updated_by)" +
                            " VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18)")
                    .setParameter(1, op.id())
                    .setParameter(2, op.preset())
                    .setParameter(3, op.displayName())
                    .setParameter(4, op.issuerUrl())
                    .setParameter(5, op.authorizeEndpoint())
                    .setParameter(6, op.tokenEndpoint())
                    .setParameter(7, op.jwksUri())
                    .setParameter(8, op.userinfoEndpoint())
                    .setParameter(9, op.discoveredIssuer())
                    .setParameter(10, op.discoveredAt() != null ? ts(op.discoveredAt()) : null)
                    .setParameter(11, op.clientId())
                    .setParameter(12, op.clientSecret())
                    .setParameter(13, op.scopes())
                    .setParameter(14, op.allowedDomains())
                    .setParameter(15, op.enabled() ? 1 : 0)
                    .setParameter(16, ts(op.createdAt()))
                    .setParameter(17, ts(op.updatedAt()))
                    .setParameter(18, op.updatedBy())
                    .executeUpdate();
        }

        // --- Users -----------------------------------------------------------
        for (var u : safe(p.users())) {
            em.createNativeQuery(
                            "INSERT INTO users (id, name, email, nickname, enabled, is_admin," +
                            " oidc_provider, oidc_subject, preferred_locale, created_at," +
                            " oidc_custom_provider_id)" +
                            " VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)")
                    .setParameter(1, u.id())
                    .setParameter(2, u.name())
                    .setParameter(3, u.email())
                    .setParameter(4, u.nickname())
                    .setParameter(5, u.enabled() ? 1 : 0)
                    .setParameter(6, u.isAdmin() ? 1 : 0)
                    .setParameter(7, u.oidcProvider())
                    .setParameter(8, u.oidcSubject())
                    .setParameter(9, u.preferredLocale())
                    .setParameter(10, ts(u.createdAt()))
                    // Pre-issue-#69 exports lack this field → null, same as a user
                    // who never authenticated via a custom provider.
                    .setParameter(11, u.oidcCustomProviderId())
                    .executeUpdate();
        }

        // --- Roles -----------------------------------------------------------
        for (var r : safe(p.roles())) {
            em.createNativeQuery(
                            "INSERT INTO roles (id, name, description, auto_all, created_at)" +
                            " VALUES (?1,?2,?3,?4,?5)")
                    .setParameter(1, r.id())
                    .setParameter(2, r.name())
                    .setParameter(3, r.description())
                    .setParameter(4, r.autoAll() ? 1 : 0)
                    .setParameter(5, ts(r.createdAt()))
                    .executeUpdate();
        }

        // --- Role memberships ------------------------------------------------
        for (var m : safe(p.roleMemberships())) {
            em.createNativeQuery(
                            "INSERT INTO user_roles (user_id, role_id) VALUES (?1,?2)")
                    .setParameter(1, m.userId())
                    .setParameter(2, m.roleId())
                    .executeUpdate();
        }

        // --- Peers -----------------------------------------------------------
        for (var peer : safe(p.peers())) {
            em.createNativeQuery(
                            "INSERT INTO peers (id, user_id, name, public_key, assigned_ip," +
                            " enabled, private_key_pem, type, site_allowed_cidrs," +
                            " device_type, preshared_key," +
                            " total_rx_bytes, total_tx_bytes," +
                            " last_sampled_rx_bytes, last_sampled_tx_bytes, created_at," +
                            " lat, lng, location_label, valid_until, enabled_source)" +
                            " VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,0,0,0,0,?12,?13,?14,?15,?16,?17)")
                    .setParameter(1, peer.id())
                    .setParameter(2, peer.userId())
                    .setParameter(3, peer.name())
                    .setParameter(4, peer.publicKey())
                    .setParameter(5, peer.assignedIp())
                    .setParameter(6, peer.enabled() ? 1 : 0)
                    .setParameter(7, peer.privateKeyPem())
                    .setParameter(8, peer.type())
                    .setParameter(9, peer.siteAllowedCidrs())
                    .setParameter(10, peer.deviceType())
                    .setParameter(11, peer.presharedKey())
                    .setParameter(12, ts(peer.createdAt()))
                    .setParameter(13, peer.lat())
                    .setParameter(14, peer.lng())
                    .setParameter(15, peer.locationLabel())
                    // Pre-Peer-Scheduler exports lack these fields → null (no expiry,
                    // no recorded source of the current enabled state).
                    .setParameter(16, peer.validUntil() != null ? ts(peer.validUntil()) : null)
                    .setParameter(17, peer.enabledSource())
                    .executeUpdate();
        }

        // --- Peer schedules (#47/Z.58) ----------------------------------------
        for (var ps : safe(p.peerSchedules())) {
            em.createNativeQuery(
                            "INSERT INTO peer_schedules" +
                            " (id, peer_id, weekday_mask, active_from, active_to, created_at, updated_at)" +
                            " VALUES (?1,?2,?3,?4,?5,?6,?7)")
                    .setParameter(1, ps.id())
                    .setParameter(2, ps.peerId())
                    .setParameter(3, ps.weekdayMask())
                    .setParameter(4, ps.activeFrom())
                    .setParameter(5, ps.activeTo())
                    .setParameter(6, ts(ps.createdAt()))
                    .setParameter(7, ts(ps.updatedAt()))
                    .executeUpdate();
        }

        // --- Sites -----------------------------------------------------------
        for (var site : safe(p.sites())) {
            em.createNativeQuery(
                            "INSERT INTO sites (id, name, cidr, description," +
                            " gateway_peer_id, created_at)" +
                            " VALUES (?1,?2,?3,?4,?5,?6)")
                    .setParameter(1, site.id())
                    .setParameter(2, site.name())
                    .setParameter(3, site.cidr())
                    .setParameter(4, site.description())
                    .setParameter(5, site.gatewayPeerId())
                    .setParameter(6, ts(site.createdAt()))
                    .executeUpdate();
        }

        // --- Resources -------------------------------------------------------
        for (var res : safe(p.resources())) {
            em.createNativeQuery(
                            "INSERT INTO resources (id, site_id, name, ip, description, type," +
                            " max_concurrent_users, max_reservation_minutes, auto_approve_reservations, created_at)" +
                            " VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)")
                    .setParameter(1, res.id())
                    .setParameter(2, res.siteId())
                    .setParameter(3, res.name())
                    .setParameter(4, res.ip())
                    .setParameter(5, res.description())
                    .setParameter(6, res.type())
                    .setParameter(7, res.maxConcurrentUsers())
                    .setParameter(8, res.maxReservationMinutes())
                    // Absent in a pre-#72 export — fall back to the entity
                    // default rather than importing every resource as
                    // "needs an admin decision for every request".
                    .setParameter(9, (res.autoApproveReservations() == null
                            || res.autoApproveReservations()) ? 1 : 0)
                    .setParameter(10, ts(res.createdAt()))
                    .executeUpdate();
        }

        // --- Resource ports --------------------------------------------------
        for (var port : safe(p.resourcePorts())) {
            em.createNativeQuery(
                            "INSERT INTO resource_ports" +
                            " (id, resource_id, port, port_end, transport, protocol, label, created_at)" +
                            " VALUES (?1,?2,?3,?4,?5,?6,?7,?8)")
                    .setParameter(1, port.id())
                    .setParameter(2, port.resourceId())
                    .setParameter(3, port.port())
                    .setParameter(4, port.portEnd())
                    .setParameter(5, port.transport())
                    .setParameter(6, port.protocol())
                    .setParameter(7, port.label())
                    .setParameter(8, ts(port.createdAt()))
                    .executeUpdate();
        }

        // --- Port groups -----------------------------------------------------
        for (var pg : safe(p.portGroups())) {
            em.createNativeQuery(
                            "INSERT INTO port_groups (id, name, description, created_at)" +
                            " VALUES (?1,?2,?3,?4)")
                    .setParameter(1, pg.id())
                    .setParameter(2, pg.name())
                    .setParameter(3, pg.description())
                    .setParameter(4, ts(pg.createdAt()))
                    .executeUpdate();
        }

        // --- Port group members ----------------------------------------------
        for (var m : safe(p.portGroupMembers())) {
            em.createNativeQuery(
                            "INSERT INTO port_group_members" +
                            " (id, port_group_id, port, port_end, transport, protocol, label)" +
                            " VALUES (?1,?2,?3,?4,?5,?6,?7)")
                    .setParameter(1, m.id())
                    .setParameter(2, m.portGroupId())
                    .setParameter(3, m.port())
                    .setParameter(4, m.portEnd())
                    .setParameter(5, m.transport())
                    .setParameter(6, m.protocol())
                    .setParameter(7, m.label())
                    .executeUpdate();
        }

        // --- ACL grants ------------------------------------------------------
        for (var g : safe(p.roleResourceGrants())) {
            em.createNativeQuery(
                            "INSERT INTO role_resource_grants" +
                            " (id, role_id, resource_id, all_ports, created_at)" +
                            " VALUES (?1,?2,?3,?4,?5)")
                    .setParameter(1, g.id())
                    .setParameter(2, g.roleId())
                    .setParameter(3, g.resourceId())
                    .setParameter(4, g.allPorts() ? 1 : 0)
                    .setParameter(5, ts(g.createdAt()))
                    .executeUpdate();
        }

        // --- ACL type grants ("all printers in Homeoffice") ------------------
        for (var g : safe(p.roleResourceTypeGrants())) {
            em.createNativeQuery(
                            "INSERT INTO role_resource_type_grants" +
                            " (id, role_id, site_id, resource_type, created_at)" +
                            " VALUES (?1,?2,?3,?4,?5)")
                    .setParameter(1, g.id())
                    .setParameter(2, g.roleId())
                    .setParameter(3, g.siteId())
                    .setParameter(4, g.resourceType())
                    .setParameter(5, ts(g.createdAt()))
                    .executeUpdate();
        }

        // --- Direct user grants (ADR-0024) ------------------------------------
        for (var g : safe(p.userResourceGrants())) {
            em.createNativeQuery(
                            "INSERT INTO user_resource_grants" +
                            " (id, user_id, resource_id, all_ports, created_at)" +
                            " VALUES (?1,?2,?3,?4,?5)")
                    .setParameter(1, g.id())
                    .setParameter(2, g.userId())
                    .setParameter(3, g.resourceId())
                    .setParameter(4, g.allPorts() ? 1 : 0)
                    .setParameter(5, ts(g.createdAt()))
                    .executeUpdate();
        }

        // --- Direct site grants -----------------------------------------------
        for (var g : safe(p.siteResourceGrants())) {
            em.createNativeQuery(
                            "INSERT INTO site_resource_grants" +
                            " (id, site_id, resource_id, all_ports, created_at)" +
                            " VALUES (?1,?2,?3,?4,?5)")
                    .setParameter(1, g.id())
                    .setParameter(2, g.siteId())
                    .setParameter(3, g.resourceId())
                    .setParameter(4, g.allPorts() ? 1 : 0)
                    .setParameter(5, ts(g.createdAt()))
                    .executeUpdate();
        }

        // --- Grant-port links ------------------------------------------------
        for (var gpl : safe(p.grantPortLinks())) {
            em.createNativeQuery(
                            "INSERT INTO role_resource_grant_ports (grant_id, port_id)" +
                            " VALUES (?1,?2)")
                    .setParameter(1, gpl.grantId())
                    .setParameter(2, gpl.portId())
                    .executeUpdate();
        }

        // --- User-grant-port links ---------------------------------------------
        for (var ugpl : safe(p.userGrantPortLinks())) {
            em.createNativeQuery(
                            "INSERT INTO user_resource_grant_ports (grant_id, port_id)" +
                            " VALUES (?1,?2)")
                    .setParameter(1, ugpl.grantId())
                    .setParameter(2, ugpl.portId())
                    .executeUpdate();
        }

        // --- Site-grant-port links ---------------------------------------------
        for (var sgpl : safe(p.siteGrantPortLinks())) {
            em.createNativeQuery(
                            "INSERT INTO site_resource_grant_ports (grant_id, port_id)" +
                            " VALUES (?1,?2)")
                    .setParameter(1, sgpl.grantId())
                    .setParameter(2, sgpl.portId())
                    .executeUpdate();
        }

        // --- Settings: update everything except the host-specific WG fields --
        if (p.settings() != null) {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            var snap = p.settings();
            s.wgSubnet = snap.wgSubnet();
            s.wgClientAllowedIps = snap.wgClientAllowedIps();
            s.wgClientDns = snap.wgClientDns();
            s.privateKeyRetention = snap.privateKeyRetention();
            s.gravatarEnabled = snap.gravatarEnabled();
            s.oidcAutoProvision = snap.oidcAutoProvision();
            s.firewallDryRun = snap.firewallDryRun();
            s.selfServicePeerCreation = snap.selfServicePeerCreation();
            s.wgMtu = snap.wgMtu();
            s.wgIncludeMtuInConf = snap.wgIncludeMtuInConf();
            // Pre-0.13.0 exports lack this field → keep the 25 default rather than 0.
            s.wgPersistentKeepalive = snap.wgPersistentKeepalive() != null ? snap.wgPersistentKeepalive() : 25;
            // Pre-#33 exports lack these fields → fall back to the same defaults the
            // Settings entity itself uses (SPLIT/MANUAL/null).
            s.tunnelMode = snap.tunnelMode() != null ? snap.tunnelMode() : "SPLIT";
            s.allowedIpsMode = snap.allowedIpsMode() != null ? snap.allowedIpsMode() : "MANUAL";
            s.splitSupernet = snap.splitSupernet();
            s.wgSubnet6 = snap.wgSubnet6();
            s.hubLat = snap.hubLat();
            s.hubLon = snap.hubLon();
            s.hubLocationLabel = snap.hubLocationLabel();
            s.nominatimUrl = snap.nominatimUrl();
            s.ironRdpEnabled = snap.ironRdpEnabled() != null ? snap.ironRdpEnabled() : false;
            s.activityRetentionDays = snap.activityRetentionDays() != null ? snap.activityRetentionDays() : 180;
            // Pre-ADR-0023 exports lack these fields → keep the entity default (false/null).
            s.dnsResolverEnabled = snap.dnsResolverEnabled() != null ? snap.dnsResolverEnabled() : false;
            s.dnsResolverZone = snap.dnsResolverZone();
            s.dnsResolverUpstream = snap.dnsResolverUpstream();
            // Pre-ADR-0026 exports lack this field → keep the entity default (true,
            // facade enabled) rather than silently disabling automation on restore.
            s.externalApiEnabled = snap.externalApiEnabled() != null ? snap.externalApiEnabled() : true;
            // wgServerPublicKey + wgServerEndpoint: keep the target hub's own values
            s.updatedAt = Instant.now();
            s.updatedBy = "config-import";
        }

        // --- OIDC providers: update in-place (rows are pre-seeded, never deleted) ---
        for (var op : safe(p.oidcProviders())) {
            OidcProvider prov = OidcProvider.findById(op.providerKey());
            if (prov == null) continue;
            prov.enabled = op.enabled();
            prov.clientId = op.clientId();
            prov.clientSecret = op.clientSecret();
            prov.tenantId = op.tenantId();
            prov.allowedDomains = op.allowedDomains();
            prov.updatedAt = Instant.now();
            prov.updatedBy = "config-import";
        }

        // --- External-API keys (ADR-0026) -------------------------------------
        // Delete-then-reinsert, unlike the built-in OIDC providers above: these
        // are ordinary admin-created rows with arbitrary ids, not pre-seeded
        // singletons keyed by a fixed providerKey. Only the hash/prefix travel
        // (ApiKey never stores the raw key) — an already-issued key keeps
        // authenticating after this restore, since verification only ever
        // needs the hash.
        for (var k : safe(p.apiKeys())) {
            em.createNativeQuery(
                            "INSERT INTO api_keys (id, label, key_hash, key_prefix," +
                            " created_at, created_by, last_used_at, revoked_at)" +
                            " VALUES (?1,?2,?3,?4,?5,?6,?7,?8)")
                    .setParameter(1, k.id())
                    .setParameter(2, k.label())
                    .setParameter(3, k.keyHash())
                    .setParameter(4, k.keyPrefix())
                    .setParameter(5, ts(k.createdAt()))
                    .setParameter(6, k.createdBy())
                    .setParameter(7, k.lastUsedAt() != null ? ts(k.lastUsedAt()) : null)
                    .setParameter(8, k.revokedAt() != null ? ts(k.revokedAt()) : null)
                    .executeUpdate();
        }

        return new ConfigExportDto.ImportResult(
                safe(p.users()).size(),
                safe(p.roles()).size(),
                safe(p.peers()).size(),
                safe(p.sites()).size(),
                safe(p.resources()).size(),
                safe(p.portGroups()).size(),
                safe(p.roleResourceGrants()).size());
    }

    /**
     * The import writes its rows with native INSERTs, so this method — not Hibernate —
     * decides the on-disk format of every {@code created_at}. It has to match what
     * Hibernate writes for entity-managed rows, or the row is written but can never be
     * read: reads go through {@code ResultSet.getTimestamp()}, and the SQLite driver
     * parses exactly {@code yyyy-MM-dd HH:mm:ss.SSS}.
     *
     * <p>{@code Instant.toString()} would emit ISO-8601 ({@code 2026-07-05T21:05:26.847Z}).
     * SQLite is dynamically typed and stores that happily, and the import reports success —
     * but every later read of the row fails with "Error parsing time stamp" and the instance
     * is bricked by its own import. Hibernate binds instants through
     * {@code TimestampUtcAsJdbcTimestampJdbcType} with a UTC calendar, so this formatter is
     * UTC too and both writers agree. See {@code ConfigImportRoundTripTest}.
     */
    private static final DateTimeFormatter DB_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static String ts(Instant instant) {
        return DB_TIMESTAMP.format(instant != null ? instant : Instant.now());
    }

    private static <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }
}
