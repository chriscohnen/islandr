package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Drops the CHECK constraint on {@code resources.type} so a new resource type
 * (an access point, say — see {@code loop/TASK.md}, task
 * {@code resource-type-open-set}) becomes an entry in the Java-side validation
 * pattern and the GUI, not another migration. This is the last rebuild the
 * table should ever need for that reason: the constraint is gone, not moved.
 *
 * <p><b>Why this is a Java migration and not a {@code .sql} file, and why that
 * distinction is load-bearing:</b> {@code application.properties} sets
 * {@code PRAGMA foreign_keys = ON} on every pooled connection, and SQLite
 * enforces {@code ON DELETE CASCADE} on a {@code DROP TABLE} exactly as it
 * would on a row delete — confirmed empirically (two child rows before,
 * zero after) against the six tables that reference {@code resources(id)}.
 * A {@code PRAGMA foreign_keys = OFF} at the top of a plain SQL migration
 * looks like the fix and is not one: SQLite ignores that pragma while a
 * transaction is open, and Flyway runs every {@code .sql} migration inside
 * one. Only {@link #canExecuteInTransaction()} returning {@code false} makes
 * the pragma take effect, and only a Java migration can override it.
 *
 * <p>Columns are copied from the table's current shape (verified against a
 * live database, not against the migration history — V39's own column list
 * is four columns short of today's: it predates {@code dns_name},
 * {@code dns_flat} and {@code mac}, and {@code max_concurrent_users} /
 * {@code max_reservation_minutes} / {@code auto_approve_reservations} were
 * added to {@code resources} in V72 and moved to {@code resource_ports} in
 * V73 — copying V39's list here would silently drop three live columns).
 *
 * <p>{@code type} widens from {@code VARCHAR(16)} to {@code VARCHAR(32)}: the
 * point of an open set is a type wider than the sixteen characters
 * {@code "virt-host"}-era names needed, and the closed list this migration
 * removes is precisely what used to make sixteen enough.
 */
public class V80__resource_type_open_set extends BaseJavaMigration {

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");
            try {
                st.execute("""
                        CREATE TABLE resources_v80 (
                            id          VARCHAR(36)  NOT NULL PRIMARY KEY,
                            site_id     VARCHAR(36)  NOT NULL REFERENCES sites(id),
                            name        VARCHAR(255) NOT NULL,
                            ip          VARCHAR(45)  NOT NULL,
                            description TEXT         NULL,
                            type        VARCHAR(32)  NOT NULL DEFAULT 'computer',
                            created_at  TIMESTAMP    NOT NULL,
                            dns_name    VARCHAR(63),
                            dns_flat    INTEGER      NOT NULL DEFAULT 0,
                            mac         VARCHAR(17)  NULL
                        )
                        """);

                // No CASE relabeling needed (unlike V13/V39): every value valid
                // under the old CHECK is trivially valid once the CHECK is gone.
                st.execute("""
                        INSERT INTO resources_v80
                            (id, site_id, name, ip, description, type, created_at, dns_name, dns_flat, mac)
                        SELECT id, site_id, name, ip, description, type, created_at, dns_name, dns_flat, mac
                        FROM resources
                        """);

                st.execute("DROP INDEX ix_resources_site");
                st.execute("DROP INDEX ix_resources_site_ip");
                st.execute("DROP TABLE resources");
                st.execute("ALTER TABLE resources_v80 RENAME TO resources");
                st.execute("CREATE INDEX ix_resources_site ON resources (site_id)");
                st.execute("CREATE UNIQUE INDEX ix_resources_site_ip ON resources (site_id, ip)");

                // The one check that actually proves the five dependent tables
                // (resource_ports, role_resource_grants, user_resource_grants,
                // site_resource_grants, resource_reservations) survived the
                // rebuild with foreign keys off, rather than assuming it.
                // role_resource_type_grants has no FK to resources(id) at all —
                // it grants by resource_type, a column value, not a row.
                //
                // Deliberately scoped per table, not a bare "PRAGMA
                // foreign_key_check" — that form checks every table in the
                // database, and a real install can carry unrelated, pre-
                // existing orphaned rows that have nothing to do with this
                // rebuild (found empirically: a dev database here had a
                // peer_daily_activity row pointing at a long-deleted peer,
                // two months old). That is a real defect worth its own entry
                // in loop/ISSUES.md, but it must not block an unrelated
                // migration that this rebuild has no way to fix and no
                // business inspecting.
                for (String table : new String[]{
                        "resource_ports", "role_resource_grants", "user_resource_grants",
                        "site_resource_grants", "resource_reservations"}) {
                    try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check(" + table + ")")) {
                        if (rs.next()) {
                            throw new IllegalStateException(
                                    "PRAGMA foreign_key_check found a violation in " + table
                                            + " after rebuilding resources — aborting before "
                                            + "foreign key enforcement is turned back on");
                        }
                    }
                }
            } finally {
                // Must run even on failure: this is a pooled connection, and
                // leaving it with foreign_keys off would silently disable
                // enforcement for whatever reuses it next.
                st.execute("PRAGMA foreign_keys = ON");
            }
        }
    }
}
