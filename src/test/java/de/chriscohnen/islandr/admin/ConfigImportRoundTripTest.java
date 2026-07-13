package de.chriscohnen.islandr.admin;

import de.chriscohnen.islandr.acl.Role;
import de.chriscohnen.islandr.acl.RoleBootstrap;
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
}
