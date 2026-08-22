package db.migration;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Widen {@code users.oidc_provider}'s CHECK to also accept {@code 'custom'}
 * (issue #69 — generic OIDC providers alongside the two hardcoded ones).
 * {@code oidc_custom_provider_id} (added in V64) disambiguates *which*
 * custom provider when {@code oidc_provider = 'custom'}; the composite
 * uniqueness index is rebuilt to include it (via {@code COALESCE} so two
 * NULLs — every microsoft/google row — still collide as before; a bare
 * 3-column unique index would let SQLite/Postgres treat every NULL as
 * distinct and silently stop enforcing the microsoft/google uniqueness it
 * has today).
 *
 * <p>Same vendor-specific-DDL reasoning as {@link V37__peer_user_id_nullable}:
 * PostgreSQL gets a plain {@code DROP CONSTRAINT}/{@code ADD CONSTRAINT} pair
 * (the constraint name follows Postgres's standard {@code <table>_<column>_check}
 * auto-naming for an inline column CHECK — not introspected, since this
 * project has no Postgres CI target yet to verify against; ADR-0004 tracks
 * that path). SQLite cannot ALTER a CHECK constraint, so the table is
 * rebuilt from its live {@code CREATE TABLE} text with only the CHECK
 * clause replaced — avoids hand-transcribing the full ~20-column users
 * schema and risking a mistake.
 */
@RegisterForReflection
public class V65__users_oidc_provider_widen extends BaseJavaMigration {

    @Override
    public boolean canExecuteInTransaction() {
        return false; // SQLite: PRAGMA foreign_keys can only be toggled outside a transaction
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        String product = c.getMetaData().getDatabaseProductName().toLowerCase();

        if (product.contains("postgresql")) {
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE users DROP CONSTRAINT users_oidc_provider_check");
                st.execute("ALTER TABLE users ADD CONSTRAINT users_oidc_provider_check "
                        + "CHECK (oidc_provider IS NULL OR oidc_provider IN ('microsoft', 'google', 'custom'))");
            }
            return;
        }

        // SQLite: rebuild the table with the CHECK widened.
        String createSql;
        List<String> indexSql = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type='table' AND name='users'")) {
            createSql = rs.next() ? rs.getString(1) : null;
        }
        if (createSql == null) {
            return; // no users table (fresh/foreign schema) — nothing to do
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='users' AND sql IS NOT NULL")) {
            while (rs.next()) indexSql.add(rs.getString(1));
        }

        String newCreate = createSql
                .replaceFirst("(?is)^\\s*CREATE\\s+TABLE\\s+\"?users\"?", "CREATE TABLE users_new")
                .replace("oidc_provider IN ('microsoft', 'google')",
                         "oidc_provider IN ('microsoft', 'google', 'custom')");

        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=OFF");
            st.execute(newCreate);
            st.execute("INSERT INTO users_new SELECT * FROM users");
            st.execute("DROP TABLE users");
            st.execute("ALTER TABLE users_new RENAME TO users");
            // Recreate every pre-existing index verbatim, except ix_users_oidc —
            // that one is replaced below with the COALESCE-widened definition.
            for (String s : indexSql) {
                if (!s.contains("ix_users_oidc")) st.execute(s);
            }
            st.execute("CREATE UNIQUE INDEX ix_users_oidc ON users "
                    + "(oidc_provider, oidc_subject, COALESCE(oidc_custom_provider_id, '')) "
                    + "WHERE oidc_provider IS NOT NULL");
            st.execute("PRAGMA foreign_keys=ON");
        }
    }
}
