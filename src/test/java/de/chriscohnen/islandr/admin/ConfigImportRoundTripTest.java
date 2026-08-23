package de.chriscohnen.islandr.admin;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleBootstrap;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.acl.SiteResourceGrant;
import de.chriscohnen.islandr.acl.UserResourceGrant;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.peer.PeerSchedule;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import writes its rows with native INSERTs, which bypasses Hibernate's timestamp
 * binding — so the import alone decides the on-disk format of every {@code created_at}.
 * Get that format wrong and the row is written happily (SQLite is dynamically typed and
 * stores any string) but can never be read again: Hibernate reads through
 * {@code ResultSet.getTimestamp()}, whose SQLite driver parses {@code yyyy-MM-dd HH:mm:ss.SSS}
 * and chokes on the ISO-8601 form {@code 2026-07-05T21:05:26.847Z}. Every later request
 * touching those rows then fails with "Error parsing time stamp" — peers, users and avatars
 * all return HTTP 500, and the instance is effectively bricked by its own import.
 *
 * <p>The round trip must therefore be asserted by <em>reading the rows back in a fresh
 * transaction</em>. Trusting the import's own return value is exactly what let this through:
 * the import reports success because writing never fails.
 */
@QuarkusTest
class ConfigImportRoundTripTest {

    @Inject
    ConfigService configService;

    @Test
    void rowsWrittenByTheImportCanBeReadBackAgain() {
        // A timestamp with millisecond precision — the ISO-8601 rendering of this instant
        // ("2026-07-05T21:05:26.847Z") is precisely what the SQLite driver cannot parse.
        Instant createdAt = Instant.parse("2026-07-05T21:05:26.847Z");
        String email = "roundtrip-" + UUID.randomUUID() + "@local";

        QuarkusTransaction.requiringNew().run(() -> {
            User u = new User();
            u.id = UUID.randomUUID().toString();
            u.name = "Round Trip";
            u.email = email;
            u.enabled = true;
            u.createdAt = createdAt;
            u.persist();
        });

        ConfigExportDto.Export export =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        // Wipes the covered tables and re-inserts them from the payload, so the round trip
        // restores the same state it captured.
        configService.importConfig(export);

        // The read path that broke in production: UserResource.list -> User.listAll().
        List<User> reloaded = QuarkusTransaction.requiringNew().call(() -> User.<User>listAll());

        assertThat(reloaded)
                .as("the user written by the import must be readable again")
                .extracting(u -> u.email)
                .contains(email);

        assertThat(reloaded)
                .allSatisfy(u -> assertThat(u.createdAt)
                        .as("every imported row must carry a parseable timestamp")
                        .isNotNull());

        assertThat(reloaded)
                .filteredOn(u -> email.equals(u.email))
                .singleElement()
                .satisfies(u -> assertThat(u.createdAt)
                        .as("the instant must survive the round trip unchanged")
                        .isEqualTo(createdAt));
    }

    /**
     * The snapshot used to omit {@code autoAll}, so the import re-created the "Everyone" role
     * with the flag cleared. On the next boot RoleBootstrap found no auto-membership role, tried
     * to seed a fresh one, and hit the unique index over {@code roles.name} — startup aborted and
     * the instance never came back. A flag silently dropped in an export is not a cosmetic loss.
     */
    @Test
    void theAutoMembershipFlagSurvivesTheRoundTrip() {
        ConfigExportDto.Export export =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        assertThat(export.roles())
                .filteredOn(r -> RoleBootstrap.EVERYONE_ROLE_NAME.equals(r.name()))
                .singleElement()
                .satisfies(r -> assertThat(r.autoAll())
                        .as("the export must carry the flag, not just the name")
                        .isTrue());

        configService.importConfig(export);

        Role everyone = QuarkusTransaction.requiringNew().call(() ->
                Role.<Role>find("name", RoleBootstrap.EVERYONE_ROLE_NAME).firstResult());

        assertThat(everyone).isNotNull();
        assertThat(everyone.autoAll)
                .as("the imported role must still be the auto-membership role")
                .isTrue();
        assertThat(Role.<Role>find("autoAll", true).count())
                .as("exactly one auto-membership role, or the next boot breaks")
                .isEqualTo(1);
    }

    /**
     * #33 (ADR-0017) added tunnelMode/allowedIpsMode/splitSupernet to the Settings entity but
     * the export/import round trip forgot them: SettingsSnapshot didn't carry the fields, so an
     * admin who configured SPLIT+AUTO+a splitSupernet and exported for a host migration would
     * silently get the bare entity defaults (SPLIT/MANUAL/null) back on the target host.
     */
    @Test
    void tunnelSettingsSurviveTheRoundTrip() {
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.tunnelMode = "SPLIT";
            s.allowedIpsMode = "AUTO";
            s.splitSupernet = "10.0.0.0/8";
        });

        ConfigExportDto.Export export =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        assertThat(export.settings().tunnelMode()).isEqualTo("SPLIT");
        assertThat(export.settings().allowedIpsMode()).isEqualTo("AUTO");
        assertThat(export.settings().splitSupernet()).isEqualTo("10.0.0.0/8");

        // Reset to different values first so the import below is a genuine restore, not a
        // no-op that would pass even if the fields were never wired up.
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.tunnelMode = "FULL";
            s.allowedIpsMode = "MANUAL";
            s.splitSupernet = null;
        });

        configService.importConfig(export);

        Settings reloaded =
                QuarkusTransaction.requiringNew().call(() -> Settings.findById(Settings.SINGLETON_ID));
        assertThat(reloaded.tunnelMode).isEqualTo("SPLIT");
        assertThat(reloaded.allowedIpsMode).isEqualTo("AUTO");
        assertThat(reloaded.splitSupernet).isEqualTo("10.0.0.0/8");
    }

    /**
     * An export created before #33 has null tunnelMode/allowedIpsMode/splitSupernet (the fields
     * didn't exist yet). Importing it must fall back to the same defaults the Settings entity
     * itself uses (SPLIT/MANUAL/null) rather than crash on a NOT NULL constraint.
     */
    @Test
    void legacyExportWithoutTunnelSettingsFallsBackToDefaults() {
        ConfigExportDto.Export original =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));
        ConfigExportDto.SettingsSnapshot orig = original.settings();

        ConfigExportDto.SettingsSnapshot legacySettings = new ConfigExportDto.SettingsSnapshot(
                orig.wgSubnet(), orig.wgServerPublicKey(), orig.wgServerEndpoint(),
                orig.wgClientAllowedIps(), orig.wgClientDns(), orig.privateKeyRetention(),
                orig.gravatarEnabled(), orig.oidcAutoProvision(), orig.firewallDryRun(),
                orig.selfServicePeerCreation(), orig.wgMtu(), orig.wgIncludeMtuInConf(),
                orig.wgPersistentKeepalive(),
                null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null);

        ConfigExportDto.Export legacyExport = new ConfigExportDto.Export(
                original.version(), original.exportedAt(), original.privateKeysIncluded(),
                legacySettings, original.oidcProviders(), original.users(), original.roles(),
                original.roleMemberships(), original.peers(), original.sites(),
                original.resources(), original.resourcePorts(), original.portGroups(),
                original.portGroupMembers(), original.roleResourceGrants(),
                original.grantPortLinks(), original.roleResourceTypeGrants(),
                original.userResourceGrants(), original.userGrantPortLinks(),
                original.siteResourceGrants(), original.siteGrantPortLinks(),
                original.peerSchedules());

        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.tunnelMode = "FULL";
            s.allowedIpsMode = "AUTO";
            s.splitSupernet = "10.0.0.0/8";
        });

        configService.importConfig(legacyExport);

        Settings reloaded =
                QuarkusTransaction.requiringNew().call(() -> Settings.findById(Settings.SINGLETON_ID));
        assertThat(reloaded.tunnelMode)
                .as("legacy export without tunnelMode must fall back to the entity default")
                .isEqualTo("SPLIT");
        assertThat(reloaded.allowedIpsMode)
                .as("legacy export without allowedIpsMode must fall back to the entity default")
                .isEqualTo("MANUAL");
        assertThat(reloaded.splitSupernet)
                .as("splitSupernet has no fallback — null is its own valid default")
                .isNull();
    }

    /**
     * hubLat/hubLon/hubLocationLabel drive the dashboard topology map. They were added to
     * Settings but never wired into SettingsSnapshot, so a config export silently dropped them —
     * an admin migrating hosts lost the map pin every time. wgSubnet6, nominatimUrl,
     * ironRdpEnabled and activityRetentionDays had the same gap; assert them here too rather than
     * leave the bug half-fixed.
     */
    @Test
    void hubLocationAndOtherSettingsFieldsSurviveTheRoundTrip() {
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.hubLat = 52.5200;
            s.hubLon = 13.4050;
            s.hubLocationLabel = "Berlin";
            s.wgSubnet6 = "fd11::/64";
            s.nominatimUrl = "https://nominatim.example.org";
            s.ironRdpEnabled = true;
            s.activityRetentionDays = 30;
        });

        ConfigExportDto.Export export =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        assertThat(export.settings().hubLat()).isEqualTo(52.5200);
        assertThat(export.settings().hubLon()).isEqualTo(13.4050);
        assertThat(export.settings().hubLocationLabel()).isEqualTo("Berlin");
        assertThat(export.settings().wgSubnet6()).isEqualTo("fd11::/64");
        assertThat(export.settings().nominatimUrl()).isEqualTo("https://nominatim.example.org");
        assertThat(export.settings().ironRdpEnabled()).isTrue();
        assertThat(export.settings().activityRetentionDays()).isEqualTo(30);

        // Reset to different values first so the import below is a genuine restore, not a
        // no-op that would pass even if the fields were never wired up.
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.hubLat = null;
            s.hubLon = null;
            s.hubLocationLabel = null;
            s.wgSubnet6 = null;
            s.nominatimUrl = null;
            s.ironRdpEnabled = false;
            s.activityRetentionDays = 180;
        });

        configService.importConfig(export);

        Settings reloaded =
                QuarkusTransaction.requiringNew().call(() -> Settings.findById(Settings.SINGLETON_ID));
        assertThat(reloaded.hubLat).isEqualTo(52.5200);
        assertThat(reloaded.hubLon).isEqualTo(13.4050);
        assertThat(reloaded.hubLocationLabel).isEqualTo("Berlin");
        assertThat(reloaded.wgSubnet6).isEqualTo("fd11::/64");
        assertThat(reloaded.nominatimUrl).isEqualTo("https://nominatim.example.org");
        assertThat(reloaded.ironRdpEnabled).isTrue();
        assertThat(reloaded.activityRetentionDays).isEqualTo(30);
    }

    /**
     * A legacy export predating these fields has them all null. Importing it must fall back to
     * the same defaults the Settings entity itself uses (false/180), not crash on the NOT NULL
     * columns backing ironRdpEnabled/activityRetentionDays.
     */
    @Test
    void legacyExportWithoutHubOrIronRdpSettingsFallsBackToDefaults() {
        ConfigExportDto.Export original =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));
        ConfigExportDto.SettingsSnapshot orig = original.settings();

        ConfigExportDto.SettingsSnapshot legacySettings = new ConfigExportDto.SettingsSnapshot(
                orig.wgSubnet(), orig.wgServerPublicKey(), orig.wgServerEndpoint(),
                orig.wgClientAllowedIps(), orig.wgClientDns(), orig.privateKeyRetention(),
                orig.gravatarEnabled(), orig.oidcAutoProvision(), orig.firewallDryRun(),
                orig.selfServicePeerCreation(), orig.wgMtu(), orig.wgIncludeMtuInConf(),
                orig.wgPersistentKeepalive(),
                orig.tunnelMode(), orig.allowedIpsMode(), orig.splitSupernet(),
                null, null, null, null, null, null, null,
                null, null, null, null);

        ConfigExportDto.Export legacyExport = new ConfigExportDto.Export(
                original.version(), original.exportedAt(), original.privateKeysIncluded(),
                legacySettings, original.oidcProviders(), original.users(), original.roles(),
                original.roleMemberships(), original.peers(), original.sites(),
                original.resources(), original.resourcePorts(), original.portGroups(),
                original.portGroupMembers(), original.roleResourceGrants(),
                original.grantPortLinks(), original.roleResourceTypeGrants(),
                original.userResourceGrants(), original.userGrantPortLinks(),
                original.siteResourceGrants(), original.siteGrantPortLinks(),
                original.peerSchedules());

        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.ironRdpEnabled = true;
            s.activityRetentionDays = 30;
        });

        configService.importConfig(legacyExport);

        Settings reloaded =
                QuarkusTransaction.requiringNew().call(() -> Settings.findById(Settings.SINGLETON_ID));
        assertThat(reloaded.ironRdpEnabled)
                .as("legacy export without ironRdpEnabled must fall back to the entity default")
                .isFalse();
        assertThat(reloaded.activityRetentionDays)
                .as("legacy export without activityRetentionDays must fall back to the entity default")
                .isEqualTo(180);
        assertThat(reloaded.hubLat)
                .as("hubLat has no fallback — null is its own valid default")
                .isNull();
    }

    @Test
    void roundTrip_preservesDirectUserGrants() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "roundtrip-usergrant-" + suffix + "@local";

        String userId = QuarkusTransaction.requiringNew().call(() -> {
            User u = User.createNew("Round Trip User " + suffix, email);
            u.persist();
            return u.id;
        });
        String resourceId = QuarkusTransaction.requiringNew().call(() -> {
            Site site = Site.createNew("RoundTripSite-" + suffix, "10.66.0.0/16", null);
            site.persist();
            Resource res = Resource.createNew(site.id, "RoundTripRes", "10.66.0.5", null, "computer");
            res.persist();
            return res.id;
        });
        QuarkusTransaction.requiringNew().run(() -> {
            UserResourceGrant.createNew(userId, resourceId, true).persist();
        });

        ConfigExportDto.Export exported =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        assertThat(exported.userResourceGrants())
                .filteredOn(g -> userId.equals(g.userId()) && resourceId.equals(g.resourceId()))
                .singleElement()
                .satisfies(g -> assertThat(g.allPorts()).isTrue());

        configService.importConfig(exported);

        List<UserResourceGrant> reloaded = QuarkusTransaction.requiringNew().call(() ->
                UserResourceGrant.<UserResourceGrant>list("userId = ?1 and resourceId = ?2", userId, resourceId));

        assertThat(reloaded)
                .as("the direct user-grant written by the import must be readable again")
                .hasSize(1);
    }

    @Test
    void roundTrip_preservesDirectSiteGrants() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        String siteId = QuarkusTransaction.requiringNew().call(() -> {
            Site site = Site.createNew("RoundTripGrantingSite-" + suffix, "10.70.0.0/16", null);
            site.persist();
            return site.id;
        });
        String resourceId = QuarkusTransaction.requiringNew().call(() -> {
            Site site = Site.createNew("RoundTripResourceSite-" + suffix, "10.71.0.0/16", null);
            site.persist();
            Resource res = Resource.createNew(site.id, "RoundTripRes2", "10.71.0.5", null, "computer");
            res.persist();
            return res.id;
        });
        QuarkusTransaction.requiringNew().run(() -> {
            SiteResourceGrant.createNew(siteId, resourceId, true).persist();
        });

        ConfigExportDto.Export exported =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        assertThat(exported.siteResourceGrants())
                .filteredOn(g -> siteId.equals(g.siteId()) && resourceId.equals(g.resourceId()))
                .singleElement()
                .satisfies(g -> assertThat(g.allPorts()).isTrue());

        configService.importConfig(exported);

        List<SiteResourceGrant> reloaded = QuarkusTransaction.requiringNew().call(() ->
                SiteResourceGrant.<SiteResourceGrant>list("siteId = ?1 and resourceId = ?2", siteId, resourceId));

        assertThat(reloaded)
                .as("the direct site-grant written by the import must be readable again")
                .hasSize(1);
    }

    /**
     * The external automation API's opt-out toggle (ADR-0026, Settings.externalApiEnabled)
     * is a global setting like any other — it must travel with a config export, same as
     * gravatarEnabled/selfServicePeerCreation/etc.
     */
    @Test
    void externalApiEnabledSurvivesTheRoundTrip() {
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.externalApiEnabled = false;
        });

        ConfigExportDto.Export export =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));
        assertThat(export.settings().externalApiEnabled()).isFalse();

        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.externalApiEnabled = true;
        });

        configService.importConfig(export);

        Settings reloaded =
                QuarkusTransaction.requiringNew().call(() -> Settings.findById(Settings.SINGLETON_ID));
        assertThat(reloaded.externalApiEnabled).isFalse();
    }

    /**
     * A legacy export predating ADR-0026 has externalApiEnabled = null. Importing it must fall
     * back to the entity default (true, facade enabled) rather than silently disabling
     * automation on every restore of an older backup.
     */
    @Test
    void legacyExportWithoutExternalApiEnabledFallsBackToDefault() {
        ConfigExportDto.Export original =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));
        ConfigExportDto.SettingsSnapshot orig = original.settings();

        ConfigExportDto.SettingsSnapshot legacySettings = new ConfigExportDto.SettingsSnapshot(
                orig.wgSubnet(), orig.wgServerPublicKey(), orig.wgServerEndpoint(),
                orig.wgClientAllowedIps(), orig.wgClientDns(), orig.privateKeyRetention(),
                orig.gravatarEnabled(), orig.oidcAutoProvision(), orig.firewallDryRun(),
                orig.selfServicePeerCreation(), orig.wgMtu(), orig.wgIncludeMtuInConf(),
                orig.wgPersistentKeepalive(),
                orig.tunnelMode(), orig.allowedIpsMode(), orig.splitSupernet(),
                orig.wgSubnet6(), orig.hubLat(), orig.hubLon(), orig.hubLocationLabel(),
                orig.nominatimUrl(), orig.ironRdpEnabled(), orig.activityRetentionDays(),
                orig.dnsResolverEnabled(), orig.dnsResolverZone(), orig.dnsResolverUpstream(),
                null);

        ConfigExportDto.Export legacyExport = new ConfigExportDto.Export(
                original.version(), original.exportedAt(), original.privateKeysIncluded(),
                legacySettings, original.oidcProviders(), original.users(), original.roles(),
                original.roleMemberships(), original.peers(), original.sites(),
                original.resources(), original.resourcePorts(), original.portGroups(),
                original.portGroupMembers(), original.roleResourceGrants(),
                original.grantPortLinks(), original.roleResourceTypeGrants(),
                original.userResourceGrants(), original.userGrantPortLinks(),
                original.siteResourceGrants(), original.siteGrantPortLinks(),
                original.peerSchedules());

        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.externalApiEnabled = false;
        });

        configService.importConfig(legacyExport);

        Settings reloaded =
                QuarkusTransaction.requiringNew().call(() -> Settings.findById(Settings.SINGLETON_ID));
        assertThat(reloaded.externalApiEnabled)
                .as("legacy export without externalApiEnabled must fall back to the entity default")
                .isTrue();
    }

    /**
     * Ad-hoc temporary direct grants (#70) carry a validUntil and are meant to expire on their
     * own via UserGrantExpiryJob — a config export must never resurrect one on restore, since
     * it may already be stale by the time the backup is imported. Only the permanent grant
     * survives the round trip; the temporary one is excluded entirely.
     */
    @Test
    void temporaryUserGrantsAreExcludedFromExport() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "roundtrip-tempgrant-" + suffix + "@local";

        String userId = QuarkusTransaction.requiringNew().call(() -> {
            User u = User.createNew("Temp Grant User " + suffix, email);
            u.persist();
            return u.id;
        });
        String resourceId = QuarkusTransaction.requiringNew().call(() -> {
            Site site = Site.createNew("TempGrantSite-" + suffix, "10.72.0.0/16", null);
            site.persist();
            Resource res = Resource.createNew(site.id, "TempGrantRes", "10.72.0.5", null, "computer");
            res.persist();
            return res.id;
        });
        String permanentResourceId = QuarkusTransaction.requiringNew().call(() -> {
            Site site = Site.createNew("PermGrantSite-" + suffix, "10.73.0.0/16", null);
            site.persist();
            Resource res = Resource.createNew(site.id, "PermGrantRes", "10.73.0.5", null, "computer");
            res.persist();
            return res.id;
        });
        QuarkusTransaction.requiringNew().run(() -> {
            UserResourceGrant temp = UserResourceGrant.createNew(userId, resourceId, true);
            temp.validUntil = Instant.parse("2099-01-01T00:00:00Z");
            temp.persist();
            UserResourceGrant.createNew(userId, permanentResourceId, true).persist();
        });

        ConfigExportDto.Export exported =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        assertThat(exported.userResourceGrants())
                .as("the temporary grant must not be part of a config snapshot")
                .noneMatch(g -> resourceId.equals(g.resourceId()));
        assertThat(exported.userResourceGrants())
                .as("the permanent grant must still be exported")
                .anyMatch(g -> permanentResourceId.equals(g.resourceId()));
    }

    /**
     * Peer-Scheduler (#47/Z.58) state — the peer's own validUntil/enabledSource, and any
     * recurring weekly schedule — must survive a config export/import round trip; a restore
     * that silently drops a configured time-restricted access window is a real regression, not
     * a cosmetic one.
     */
    @Test
    void peerScheduleAndValidUntilSurviveTheRoundTrip() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        String peerId = QuarkusTransaction.requiringNew().call(() -> {
            Peer peer = Peer.createNew(null, "ScheduledPeer-" + suffix, "pk-" + suffix, "10.80.0.5");
            peer.validUntil = Instant.parse("2099-06-01T00:00:00Z");
            peer.enabledSource = "schedule";
            peer.persist();
            return peer.id;
        });
        QuarkusTransaction.requiringNew().run(() -> {
            PeerSchedule.createNew(peerId, 0b0011111,
                    java.time.LocalTime.of(8, 0), java.time.LocalTime.of(18, 0)).persist();
        });

        ConfigExportDto.Export exported =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        assertThat(exported.peers())
                .filteredOn(p -> peerId.equals(p.id()))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.validUntil()).isEqualTo(Instant.parse("2099-06-01T00:00:00Z"));
                    assertThat(p.enabledSource()).isEqualTo("schedule");
                });
        assertThat(exported.peerSchedules())
                .filteredOn(ps -> peerId.equals(ps.peerId()))
                .singleElement()
                .satisfies(ps -> {
                    assertThat(ps.weekdayMask()).isEqualTo(0b0011111);
                    assertThat(ps.activeFrom()).isEqualTo("08:00");
                    assertThat(ps.activeTo()).isEqualTo("18:00");
                });

        configService.importConfig(exported);

        Peer reloadedPeer = QuarkusTransaction.requiringNew().call(() -> Peer.<Peer>findById(peerId));
        assertThat(reloadedPeer.validUntil).isEqualTo(Instant.parse("2099-06-01T00:00:00Z"));
        assertThat(reloadedPeer.enabledSource).isEqualTo("schedule");

        PeerSchedule reloadedSchedule =
                QuarkusTransaction.requiringNew().call(() -> PeerSchedule.findByPeer(peerId));
        assertThat(reloadedSchedule).isNotNull();
        assertThat(reloadedSchedule.weekdayMask).isEqualTo(0b0011111);
        assertThat(reloadedSchedule.activeFrom).isEqualTo("08:00");
        assertThat(reloadedSchedule.activeTo).isEqualTo("18:00");
    }
}
