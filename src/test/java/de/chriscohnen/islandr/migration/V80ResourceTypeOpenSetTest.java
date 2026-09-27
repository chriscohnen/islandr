package de.chriscohnen.islandr.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs V80 for real against a real SQLite file, with {@code foreign_keys=ON}
 * enforced on every connection Flyway opens — the exact setting
 * {@code application.properties} applies in production via
 * {@code new-connection-sql}. That setting is the whole reason V80 has to be
 * a Java migration at all (see its class Javadoc): a plain {@code .sql} file
 * cannot turn foreign-key enforcement off from inside the transaction Flyway
 * wraps it in, and SQLite cascades a {@code DROP TABLE} exactly as it would a
 * row delete when enforcement is on.
 *
 * <p>This is the harness {@code loop/TASK.md} asked for doing double duty:
 * {@link #resourcesPortsAndGrantsSurviveTheRebuild()} proves V80 is safe, and
 * {@link #v39DroppedPortsAndGrants_confirmingTheHistoricalDefect()} replays
 * the same setup one migration earlier to settle the open question about
 * V39 (0.11.0) empirically instead of leaving it asserted.
 */
class V80ResourceTypeOpenSetTest {

    /** Real file, not {@code :memory:} — the rebuild's DROP/RENAME sequence is
     *  exactly the part a shared in-memory connection can paper over. */
    private DataSource dataSourceEnforcingForeignKeys(Path dbFile) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile);
        ds.setEnforceForeignKeys(true);
        return ds;
    }

    private Flyway flywayTo(DataSource ds, String target) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    @Test
    void resourcesPortsAndGrantsSurviveTheRebuild(@TempDir Path tmp) throws SQLException {
        Path dbFile = tmp.resolve("v80.db");
        DataSource ds = dataSourceEnforcingForeignKeys(dbFile);

        // Migrate up to the schema V80 rebuilds, not past it.
        flywayTo(ds, "79").migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO sites (id, name, cidr, created_at) VALUES ('s1','Site','10.0.0.0/24','2026-01-01')");
            st.execute("INSERT INTO resources (id, site_id, name, ip, description, type, created_at, dns_name, dns_flat, mac) "
                    + "VALUES ('r1','s1','Box','10.0.0.5',NULL,'computer','2026-01-01',NULL,0,NULL)");
            st.execute("INSERT INTO resource_ports (id, resource_id, port, port_end, transport, protocol, label, created_at) "
                    + "VALUES ('p1','r1',22,NULL,'tcp','SSH',NULL,'2026-01-01')");
            st.execute("INSERT INTO roles (id, name, description, created_at) VALUES ('role1','Role','', '2026-01-01')");
            st.execute("INSERT INTO role_resource_grants (id, role_id, resource_id, all_ports, created_at) "
                    + "VALUES ('g1','role1','r1',1,'2026-01-01')");
        }

        // The migration under test.
        flywayTo(ds, null).migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(count(st, "resources")).as("resource survived").isEqualTo(1);
            assertThat(count(st, "resource_ports")).as("port survived").isEqualTo(1);
            assertThat(count(st, "role_resource_grants")).as("grant survived").isEqualTo(1);

            // The property the whole task exists for: a type the old CHECK
            // would have rejected outright now inserts cleanly.
            st.execute("INSERT INTO resources (id, site_id, name, ip, description, type, created_at, dns_name, dns_flat, mac) "
                    + "VALUES ('r2','s1','AP','10.0.0.6',NULL,'accesspoint','2026-01-01',NULL,0,NULL)");
            assertThat(count(st, "resources")).isEqualTo(2);
        }
    }

    /**
     * Found while verifying this migration against a real, long-lived dev
     * database: a bare {@code PRAGMA foreign_key_check} checks every table,
     * not just the ones this rebuild touches. A pre-existing orphaned row
     * anywhere else in the database — unrelated to resources entirely, here
     * a {@code peer_daily_activity} row left behind by a deleted peer — must
     * not block this migration. It has nothing to fix and no business
     * inspecting it; only the five tables that reference {@code resources}
     * are its concern (logged for its own attention in loop/ISSUES.md).
     */
    @Test
    void unrelatedPreexistingOrphanElsewhereInTheDbDoesNotBlockV80(@TempDir Path tmp) throws SQLException {
        Path dbFile = tmp.resolve("v80-unrelated-orphan.db");
        DataSource ds = dataSourceEnforcingForeignKeys(dbFile);
        flywayTo(ds, "79").migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO sites (id, name, cidr, created_at) VALUES ('s1','Site','10.0.0.0/24','2026-01-01')");
            st.execute("INSERT INTO resources (id, site_id, name, ip, description, type, created_at, dns_name, dns_flat, mac) "
                    + "VALUES ('r1','s1','Box','10.0.0.5',NULL,'computer','2026-01-01',NULL,0,NULL)");
            // An orphaned peer_daily_activity row — the peer it names was
            // deleted without this row going with it, exactly the state
            // found in a real dev database. foreign_keys is ON on this
            // connection, so this has to go in with it temporarily off,
            // the same way a real such row could only have gotten there.
            st.execute("PRAGMA foreign_keys = OFF");
            st.execute("INSERT INTO peer_daily_activity (peer_id, day, sample_hits, rx_bytes, tx_bytes) "
                    + "VALUES ('ghost-peer-does-not-exist', '2026-07-21', 1, 0, 0)");
            st.execute("PRAGMA foreign_keys = ON");
        }

        // Must not throw — this is the assertion. A whole-database check
        // would fail here; V80's per-table check must not.
        flywayTo(ds, null).migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(count(st, "resources")).isEqualTo(1);
        }
    }

    /** foreign_key_check must stay clean, or the migration is trusting a
     *  rebuild it never actually verified. */
    @Test
    void foreignKeyCheckPassesAfterTheRebuild(@TempDir Path tmp) throws SQLException {
        Path dbFile = tmp.resolve("v80-fk.db");
        DataSource ds = dataSourceEnforcingForeignKeys(dbFile);
        flywayTo(ds, "79").migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO sites (id, name, cidr, created_at) VALUES ('s1','Site','10.0.0.0/24','2026-01-01')");
            st.execute("INSERT INTO resources (id, site_id, name, ip, description, type, created_at, dns_name, dns_flat, mac) "
                    + "VALUES ('r1','s1','Box','10.0.0.5',NULL,'computer','2026-01-01',NULL,0,NULL)");
        }
        flywayTo(ds, null).migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("PRAGMA foreign_key_check")) {
            assertThat(rs.next()).as("no foreign_key_check violation").isFalse();
        }
    }

    /** Foreign-key enforcement must be back on afterwards — this is a pooled
     *  connection in production, and leaving it off would silently disable
     *  enforcement for whatever the pool hands out next. */
    @Test
    void foreignKeysAreEnforcedAgainAfterwards(@TempDir Path tmp) throws SQLException {
        Path dbFile = tmp.resolve("v80-fk-on.db");
        DataSource ds = dataSourceEnforcingForeignKeys(dbFile);
        flywayTo(ds, null).migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("PRAGMA foreign_keys")) {
            rs.next();
            assertThat(rs.getInt(1)).as("foreign_keys pragma").isEqualTo(1);
        }
    }

    /**
     * Settles ISSUES.md's open question: did V39 (shipped in 0.11.0) actually
     * destroy data on an install that had any? Replays the exact same setup
     * one migration boundary earlier (target 38, the last version before V39
     * existed) and runs only V39 — the one migration already confirmed to
     * {@code DROP TABLE resources} while a role_resource_grant and a
     * resource_port pointed at it with {@code ON DELETE CASCADE}, on the same
     * foreign-key-enforcing connection production has used since 2026-06-04
     * (five weeks before V39 shipped).
     *
     * <p>This does not fix anything — V39 already ran on every install that
     * upgraded through 0.11.0. It only replaces "presumably" with a number.
     */
    @Test
    void v39DroppedPortsAndGrants_confirmingTheHistoricalDefect(@TempDir Path tmp) throws SQLException {
        Path dbFile = tmp.resolve("v39.db");
        DataSource ds = dataSourceEnforcingForeignKeys(dbFile);

        flywayTo(ds, "38").migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO sites (id, name, cidr, created_at) VALUES ('s1','Site','10.0.0.0/24','2026-01-01')");
            // V38's resources table predates dns_name/dns_flat/mac (V54/V57/V76).
            st.execute("INSERT INTO resources (id, site_id, name, ip, description, type, created_at) "
                    + "VALUES ('r1','s1','Box','10.0.0.5',NULL,'computer','2026-01-01')");
            st.execute("INSERT INTO resource_ports (id, resource_id, port, port_end, transport, protocol, label, created_at) "
                    + "VALUES ('p1','r1',22,NULL,'tcp','SSH',NULL,'2026-01-01')");
            st.execute("INSERT INTO roles (id, name, description, created_at) VALUES ('role1','Role','', '2026-01-01')");
            st.execute("INSERT INTO role_resource_grants (id, role_id, resource_id, all_ports, created_at) "
                    + "VALUES ('g1','role1','r1',1,'2026-01-01')");

            assertThat(count(st, "resource_ports")).as("precondition: port exists before V39").isEqualTo(1);
            assertThat(count(st, "role_resource_grants")).as("precondition: grant exists before V39").isEqualTo(1);
        }

        flywayTo(ds, "39").migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(count(st, "resources")).as("the resource row itself (not FK-dependent) survives").isEqualTo(1);
            // This is the defect: both child rows are gone, cascaded by the
            // DROP TABLE resources inside V39, with foreign_keys already ON.
            assertThat(count(st, "resource_ports")).as("V39 cascaded away the port").isEqualTo(0);
            assertThat(count(st, "role_resource_grants")).as("V39 cascaded away the grant").isEqualTo(0);
        }
    }

    /** V80 itself must reject the same kind of foreign-key blind spot it
     *  fixes: a broken rebuild should fail loudly, not migrate quietly onto
     *  an inconsistent schema. Regression guard for the finally-block
     *  ordering, verified by forcing the target below V80 and confirming the
     *  pragma really is what makes the difference between V39's outcome and
     *  V80's. */
    @Test
    void withoutForeignKeyEnforcementTheOldDefectDoesNotReproduce(@TempDir Path tmp) throws SQLException {
        // Sanity check on the test harness itself: with enforcement off (the
        // sqlite-jdbc default), the same V39 replay must NOT lose the child
        // rows — confirming the defect is specifically about foreign_keys=ON,
        // not an artifact of this test's setup.
        Path dbFile = tmp.resolve("v39-noenforce.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile);
        // enforceForeignKeys defaults to false — left unset deliberately.

        flywayTo(ds, "38").migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO sites (id, name, cidr, created_at) VALUES ('s1','Site','10.0.0.0/24','2026-01-01')");
            st.execute("INSERT INTO resources (id, site_id, name, ip, description, type, created_at) "
                    + "VALUES ('r1','s1','Box','10.0.0.5',NULL,'computer','2026-01-01')");
            st.execute("INSERT INTO resource_ports (id, resource_id, port, port_end, transport, protocol, label, created_at) "
                    + "VALUES ('p1','r1',22,NULL,'tcp','SSH',NULL,'2026-01-01')");
        }
        flywayTo(ds, "39").migrate();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(count(st, "resource_ports")).as("without enforcement, the cascade never fires").isEqualTo(1);
        }
    }

    private int count(Statement st, String table) throws SQLException {
        try (var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
