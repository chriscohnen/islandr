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
 * Make {@code peers.user_id} nullable — site peers have no owning user
 * (commit 43faed0), but the column has been NOT NULL since V2, so creating a
 * site peer failed at insert.
 *
 * <p>This is a Java migration because the DDL is vendor-specific:
 * <ul>
 *   <li>PostgreSQL: a plain {@code ALTER TABLE ... DROP NOT NULL}.
 *   <li>SQLite: cannot drop a column constraint, so the table is rebuilt. To
 *       avoid mis-transcribing the 20-column schema, it reads the live
 *       {@code CREATE TABLE} from {@code sqlite_master} and strips only the
 *       {@code user_id} {@code NOT NULL}; {@code SELECT *} then copies every row
 *       (column order is unchanged). The inbound FK {@code sites.gateway_peer_id}
 *       requires {@code foreign_keys=OFF} during the swap, which only takes
 *       effect outside a transaction — hence {@link #canExecuteInTransaction()}.
 * </ul>
 */
@RegisterForReflection
public class V37__peer_user_id_nullable extends BaseJavaMigration {

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
                st.execute("ALTER TABLE peers ALTER COLUMN user_id DROP NOT NULL");
            }
            return;
        }

        // SQLite: rebuild the table with only the user_id NOT NULL removed.
        String createSql;
        List<String> indexSql = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type='table' AND name='peers'")) {
            createSql = rs.next() ? rs.getString(1) : null;
        }
        if (createSql == null) {
            return; // no peers table (fresh/foreign schema) — nothing to do
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='peers' AND sql IS NOT NULL")) {
            while (rs.next()) indexSql.add(rs.getString(1));
        }

        String newCreate = createSql
                .replaceFirst("(?is)^\\s*CREATE\\s+TABLE\\s+\"?peers\"?", "CREATE TABLE peers_new")
                .replaceFirst("(?i)(user_id\\s+\\S+)\\s+NOT\\s+NULL", "$1");

        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=OFF");
            st.execute(newCreate);
            st.execute("INSERT INTO peers_new SELECT * FROM peers");
            st.execute("DROP TABLE peers");
            st.execute("ALTER TABLE peers_new RENAME TO peers");
            for (String s : indexSql) st.execute(s);
            st.execute("PRAGMA foreign_keys=ON");
        }
    }
}
