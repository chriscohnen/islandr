package de.chriscohnen.islandr.admin;

import de.chriscohnen.islandr.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the repair actually rescues a database the pre-0.12.0 import corrupted.
 *
 * <p>The three literals below are the shapes {@code Instant.toString()} really produces —
 * it drops the fraction at whole seconds and keeps micro-/nanoseconds when it has them.
 * A repair that merely swapped {@code T} for a space and dropped the {@code Z} would leave
 * the first and the third still unreadable, because the SQLite driver insists on exactly
 * three fractional digits. So all three are asserted, not just the convenient one.
 */
@QuarkusTest
class ImportedTimestampRepairTest {

    private static final String PREFIX = "repair-probe-";

    @Inject
    ImportedTimestampRepair repair;

    @Inject
    EntityManager em;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("DELETE FROM users WHERE email LIKE '" + PREFIX + "%'")
                        .executeUpdate());
    }

    @Test
    void repairsEveryIsoPrecisionTheOldImportCouldWrite() {
        insertWithRawTimestamp("none", "2026-07-05T21:05:26Z");
        insertWithRawTimestamp("millis", "2026-07-05T21:05:26.847Z");
        insertWithRawTimestamp("micros", "2026-07-05T21:05:26.847123Z");

        // Before the repair these rows are exactly as broken as they were in production:
        // reading them through Hibernate blows up on the timestamp.
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().call(() -> probes()))
                .as("the corrupted rows must be unreadable to begin with — otherwise this "
                        + "test would pass without the repair doing anything")
                .hasStackTraceContaining("Error parsing time stamp");

        repair.repair();

        List<User> repaired = QuarkusTransaction.requiringNew().call(this::probes);

        assertThat(repaired).hasSize(3);
        assertThat(byName(repaired, "none").createdAt).isEqualTo(Instant.parse("2026-07-05T21:05:26Z"));
        assertThat(byName(repaired, "millis").createdAt).isEqualTo(Instant.parse("2026-07-05T21:05:26.847Z"));
        // Sub-millisecond precision is truncated: the column's format carries three digits.
        assertThat(byName(repaired, "micros").createdAt).isEqualTo(Instant.parse("2026-07-05T21:05:26.847Z"));
    }

    /** Writes a row the way the old import did — a raw ISO-8601 string, straight past Hibernate. */
    private void insertWithRawTimestamp(String name, String isoTimestamp) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "INSERT INTO users (id, name, email, enabled, is_admin, created_at)"
                                + " VALUES (?1,?2,?3,1,0,?4)")
                        .setParameter(1, java.util.UUID.randomUUID().toString())
                        .setParameter(2, name)
                        .setParameter(3, PREFIX + name + "@local")
                        .setParameter(4, isoTimestamp)
                        .executeUpdate());
    }

    private List<User> probes() {
        return User.<User>find("email like ?1", PREFIX + "%").list();
    }

    private static User byName(List<User> users, String name) {
        return users.stream().filter(u -> name.equals(u.name)).findFirst().orElseThrow();
    }
}
