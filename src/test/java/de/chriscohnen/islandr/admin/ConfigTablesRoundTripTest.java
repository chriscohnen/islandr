package de.chriscohnen.islandr.admin;

import de.chriscohnen.islandr.acl.EdgeHub;
import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.ResourcePort;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.webhook.Webhook;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backup and restore, end to end through the table dump (export format 3).
 *
 * <p>The dump reads its tables and columns from the schema, so these tests do
 * not list fields: they fill the database, take a dump, empty every table, put
 * the dump back and demand that a second dump equals the first, row for row and
 * column for column. A column or table nobody thought about is covered anyway.
 * What the tests do name are the things that were once lost on restore (an
 * external network, DNS names, RDP options, link names, local passwords,
 * webhooks, edge hubs) so a regression says what broke, not just that two maps
 * differ.
 */
@QuarkusTest
class ConfigTablesRoundTripTest {

    @Inject ConfigTables tables;
    @Inject ConfigService configService;
    @Inject SettingsService settings;
    @PersistenceContext EntityManager em;
    @Inject javax.sql.DataSource dataSource;

    /** Seeded rows outlive their test, and assigned_ip is unique: never reuse an address. */
    private static final java.util.concurrent.atomic.AtomicInteger nextIp = new java.util.concurrent.atomic.AtomicInteger();

    /** A recognisable value in every column group that has ever been lost. */
    private record Seed(String siteId, String resourceId, String portId, String userId, String peerId,
                        String webhookId, String edgeHubId, byte[] avatar) {}

    private Seed seed() {
        return QuarkusTransaction.requiringNew().call(() -> {
            String x = UUID.randomUUID().toString().substring(0, 8);
            EdgeHub hub = EdgeHub.createNew("Edge-" + x, 52.5, 13.4, "Berlin");
            hub.persist();

            Site site = Site.createNew("Partner-" + x, "104.16.184.241/32", null);
            site.kind = Site.KIND_EXTERNAL;
            site.subdomain = "partner";
            site.dnsServerIp = "10.8.0.1";
            site.persist();

            Resource res = Resource.createNew(site.id, "icanhazip-" + x, "104.16.184.241", "a partner", "computer");
            res.dnsName = "icanhaz";
            res.dnsFlat = true;
            res.dnsAliases.add("ip");
            res.ip6 = "2606:4700::6810:b8f1";
            res.linkFqdn = "icanhazip.com";
            res.persist();

            ResourcePort port = ResourcePort.createNew(res.id, 443, null, "tcp", "HTTPS", null, "/ip",
                    false, true, "web-only");
            port.linkHost = ResourcePort.LINK_HOST_FQDN;
            port.persist();

            User user = User.createNew("Round Trip", "roundtrip-" + x + "@example.test");
            user.passwordHash = "pbkdf2$600000$salt$hash-" + x;
            byte[] avatar = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff};
            user.avatarBytes = avatar;
            user.persist();

            Peer peer = Peer.createNew(user.id, "laptop-" + x, "RoundTrip" + x + "0000000000000000000000000000=", "10.77." + (nextIp.get() / 250) + "." + (1 + nextIp.getAndIncrement() % 250));
            peer.mtu = 1380;
            peer.persistentKeepalive = 15;
            peer.assignedIpv6 = "fd00::" + x.substring(0, 4);
            peer.persist();

            Webhook hook = new Webhook();
            hook.id = UUID.randomUUID().toString();
            hook.url = "https://hooks.example.test/" + x;
            hook.eventTypes = "backup.failed";
            hook.secret = "s3cret-" + x;
            // disabled: a live hook would be updated by the dispatcher while a restore deletes it
            hook.enabled = false;
            hook.createdAt = Instant.now();
            hook.updatedAt = Instant.now();
            hook.updatedBy = "test";
            hook.persist();

            return new Seed(site.id, res.id, port.id, user.id, peer.id, hook.id, hub.id, avatar);
        });
    }

    private Map<String, List<Map<String, Object>>> dump() {
        return QuarkusTransaction.requiringNew().call(() -> tables.dump(true));
    }

    /** What a freshly installed hub looks like: every dumped table empty. */
    private void emptyEverything(Map<String, List<Map<String, Object>>> shape) {
        Map<String, List<Map<String, Object>>> empty = new LinkedHashMap<>();
        shape.keySet().forEach(t -> empty.put(t, List.of()));
        QuarkusTransaction.requiringNew().run(() -> tables.restore(empty));
    }

    private void restore(Map<String, List<Map<String, Object>>> dump) {
        QuarkusTransaction.requiringNew().run(() -> tables.restore(dump));
    }

    @Test
    void everyTableAndColumn_comesBackExactlyAsItWent() {
        seed();
        Map<String, List<Map<String, Object>>> before = dump();

        emptyEverything(before);
        assertThat(dump().get("sites")).isEmpty();
        assertThat(dump().get("resources")).isEmpty();

        restore(before);

        Map<String, List<Map<String, Object>>> after = dump();
        assertThat(after.keySet()).isEqualTo(before.keySet());
        before.forEach((table, rows) -> assertThat(after.get(table)).as("table " + table)
                .containsExactlyInAnyOrderElementsOf(rows));
    }

    @Test
    void whatWasOnceLost_survives() {
        Seed s = seed();
        Map<String, List<Map<String, Object>>> before = dump();
        emptyEverything(before);
        restore(before);

        QuarkusTransaction.requiringNew().run(() -> {
            Site site = Site.findById(s.siteId());
            assertThat(site.kind).as("an external network stays external").isEqualTo(Site.KIND_EXTERNAL);
            assertThat(site.subdomain).isEqualTo("partner");
            assertThat(site.dnsServerIp).isEqualTo("10.8.0.1");

            Resource res = Resource.findById(s.resourceId());
            assertThat(res.dnsName).isEqualTo("icanhaz");
            assertThat(res.dnsFlat).isTrue();
            assertThat(res.dnsAliases).containsExactly("ip");
            assertThat(res.ip6).isEqualTo("2606:4700::6810:b8f1");
            assertThat(res.linkFqdn).isEqualTo("icanhazip.com");

            ResourcePort port = ResourcePort.findById(s.portId());
            assertThat(port.pathPrefix).isEqualTo("/ip");
            assertThat(port.rdpFileTransfer).isTrue();
            assertThat(port.rdpAccessMode).isEqualTo("web-only");
            assertThat(port.linkHost).isEqualTo(ResourcePort.LINK_HOST_FQDN);

            User user = User.findById(s.userId());
            assertThat(user.passwordHash).as("a local account keeps its password").startsWith("pbkdf2$");
            assertThat(user.avatarBytes).as("binary columns survive the JSON").isEqualTo(s.avatar());

            Peer peer = Peer.findById(s.peerId());
            assertThat(peer.mtu).isEqualTo(1380);
            assertThat(peer.persistentKeepalive).isEqualTo(15);
            assertThat(peer.assignedIpv6).startsWith("fd00::");

            assertThat((Webhook) Webhook.findById(s.webhookId())).as("webhooks are in the backup").isNotNull();
            assertThat((EdgeHub) EdgeHub.findById(s.edgeHubId())).as("edge hubs are in the backup").isNotNull();
        });
    }

    @Test
    void theDumpSurvivesJson_asTheBackupFileDoes() throws Exception {
        seed();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules();
        ConfigExportDto.Export exported = QuarkusTransaction.requiringNew().call(() -> configService.export(true));
        assertThat(exported.version()).isEqualTo("3");

        ConfigExportDto.Export viaFile = mapper.readValue(mapper.writeValueAsBytes(exported), ConfigExportDto.Export.class);
        emptyEverything(exported.tables());
        QuarkusTransaction.requiringNew().run(() -> configService.importConfig(viaFile));

        Map<String, List<Map<String, Object>>> after = dump();
        exported.tables().forEach((table, rows) -> {
            if (table.equals("users")) return; // numbers may change width through JSON; checked column-wise below
            assertThat(after.get(table)).as("table " + table).hasSameSizeAs(rows);
        });
        assertThat(after.get("users")).extracting(r -> r.get("password_hash"))
                .containsExactlyInAnyOrderElementsOf(exported.tables().get("users").stream()
                        .map(r -> r.get("password_hash")).toList());
    }

    @Test
    void hostBoundSettingsAndRuntimeStateStayWithTheHost() {
        seed();
        String endpoint = QuarkusTransaction.requiringNew().call(() -> settings.get().wgServerEndpoint);
        Map<String, List<Map<String, Object>>> before = dump();

        assertThat(before).doesNotContainKeys("sessions", "audit_log", "resource_reservations", "backup_config");
        assertThat(before.get("settings").get(0)).doesNotContainKeys(
                "wg_server_public_key", "wg_server_endpoint", "wan_interface", "tls_key_pem", "trusted_proxies");
        assertThat(before.get("peers")).allSatisfy(p -> assertThat(p).doesNotContainKeys("last_seen_at", "connected_since"));

        QuarkusTransaction.requiringNew().run(() -> settings.get().wgServerEndpoint = "other-host.example.test:51820");
        restore(before);
        assertThat(QuarkusTransaction.requiringNew().call(() -> settings.get().wgServerEndpoint))
                .as("a restore never moves the hub's own address")
                .isEqualTo("other-host.example.test:51820");
        QuarkusTransaction.requiringNew().run(() -> settings.get().wgServerEndpoint = endpoint);
    }

    @Test
    void privateKeysTravelOnlyWhenAsked() {
        seed();
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE peers SET private_key_pem = 'PRIVATE-KEY' WHERE private_key_pem IS NULL").executeUpdate());
        Map<String, List<Map<String, Object>>> without = QuarkusTransaction.requiringNew().call(() -> tables.dump(false));
        Map<String, List<Map<String, Object>>> with = dump();
        assertThat(without.get("peers")).allSatisfy(p -> assertThat(p).doesNotContainKey("private_key_pem"));
        assertThat(with.get("peers")).anySatisfy(p -> assertThat(p.get("private_key_pem")).isEqualTo("PRIVATE-KEY"));
    }

    @Test
    void aBrokenFile_leavesTheExistingConfigurationUntouched() {
        Seed s = seed();
        Map<String, List<Map<String, Object>>> broken = new LinkedHashMap<>();
        // a resource pointing at a site that does not exist: refused when the transaction commits
        Map<String, Object> orphan = new LinkedHashMap<>();
        orphan.put("id", UUID.randomUUID().toString());
        orphan.put("site_id", "no-such-site");
        orphan.put("name", "orphan");
        orphan.put("ip", "10.1.1.1");
        orphan.put("type", "computer");
        orphan.put("created_at", "2026-01-01 00:00:00.000");
        broken.put("resources", new ArrayList<>(List.of(orphan)));

        // The test database is in memory and shared: when the failed commit makes the pool
        // drop its connection, the database goes with it unless another connection is open.
        // (A file database, as in production, has no such problem.)
        try (java.sql.Connection keepAlive = dataSource.getConnection()) {
            assertThatThrownBy(() -> restore(broken)).isInstanceOf(RuntimeException.class);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }

        // The shared in-memory cache keeps table locks until the pool has finished rolling the
        // failed connection back; read again until it has.
        Resource survivor = null;
        for (int attempt = 0; attempt < 50 && survivor == null; attempt++) {
            try {
                survivor = QuarkusTransaction.requiringNew().call(() -> (Resource) Resource.findById(s.resourceId()));
            } catch (RuntimeException locked) {
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        assertThat(survivor).as("the failed import rolled back").isNotNull();
    }

    @Test
    void anOlderFileWithoutTheDump_stillRestoresThroughTheLegacyPath() {
        seed();
        ConfigExportDto.Export current = QuarkusTransaction.requiringNew().call(() -> configService.export(false));
        ConfigExportDto.Export v2 = new ConfigExportDto.Export("2", current.exportedAt(), current.appVersion(),
                false, current.settings(), current.oidcProviders(), current.users(), current.roles(),
                current.roleMemberships(), current.peers(), current.sites(), current.resources(),
                current.resourcePorts(), current.portGroups(), current.portGroupMembers(),
                current.roleResourceGrants(), current.grantPortLinks(), current.roleResourceTypeGrants(),
                current.userResourceGrants(), current.userGrantPortLinks(), current.siteResourceGrants(),
                current.siteGrantPortLinks(), current.peerSchedules(), current.oidcCustomProviders(),
                current.apiKeys(), current.roleNetworkGrants());
        assertThat(v2.tables()).isNull();
        ConfigExportDto.ImportResult r = QuarkusTransaction.requiringNew().call(() -> configService.importConfig(v2));
        assertThat(r.sites()).isEqualTo(current.sites().size());
    }
}
