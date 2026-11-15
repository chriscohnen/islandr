package de.chriscohnen.islandr.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.Session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The export's table dump (format version 3): every column of every table,
 * read from the schema itself rather than from a list somebody has to keep in
 * step with it.
 *
 * <p>Format versions 1 and 2 named their fields by hand, and every column added
 * after 1.1.0 was silently lost on restore — an external network came back as an
 * internal one, DNS names and RDP options vanished, and the whole of webhooks
 * and edge hubs was never written at all. Here a new table or column travels
 * the moment a migration creates it; the only decision a developer can still
 * take is to leave something out, and that is the explicit {@link #EXCLUDED_TABLES}
 * and {@link #EXCLUDED_COLUMNS}, with a reason each.
 *
 * <p>Values travel as the database holds them (text, integer, real, blob), over
 * plain JDBC. That keeps a timestamp the exact string it was, which matters: the
 * SQLite driver can only read back one format, and a "helpful" conversion on the
 * way in bricks the row (see {@code ConfigImportRoundTripTest}).
 */
@ApplicationScoped
public class ConfigTables {

    /** Not configuration: state that is rebuilt, expires, or belongs to this host. */
    static final Set<String> EXCLUDED_TABLES = Set.of(
            "flyway_schema_history",
            "sessions",                 // a restore signs nobody in
            "audit_log",                // history of this hub, not its configuration
            "peer_daily_activity",      // history
            "resource_reservations",    // transient session state: nobody holds anything after a rebuild
            "firewall_state",           // what this host last applied
            "backup_config");           // the bucket the admin just typed in to restore

    /** Runtime readings of this host's WireGuard interface, stale the moment they are restored. */
    static final Map<String, Set<String>> EXCLUDED_COLUMNS = Map.of(
            "peers", Set.of("last_seen_at", "last_seen_endpoint", "connected_since",
                    "last_sampled_rx_bytes", "last_sampled_tx_bytes"));

    /**
     * The settings row exists on every install and is updated, never replaced.
     * These columns belong to the host the backup is restored onto: its WireGuard
     * identity and public address, its uplink, its certificate and the proxy in
     * front of it. A fresh install keeps its own.
     */
    static final Set<String> HOST_BOUND_SETTINGS = Set.of(
            "id", "updated_at", "updated_by",
            "wg_server_public_key", "wg_server_endpoint", "wan_interface",
            "tls_mode", "tls_cert_pem", "tls_key_pem", "tls_cert_path", "tls_key_path",
            "trusted_proxies", "client_ip_header");

    private static final String SETTINGS = "settings";
    private static final String BLOB_KEY = "b64";

    @Inject EntityManager em;

    /** Every table the export carries, in a stable order. */
    List<String> tables(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (!EXCLUDED_TABLES.contains(name)) out.add(name);
            }
        }
        return out;
    }

    private boolean excludedColumn(String table, String column, boolean includePrivateKeys) {
        if (SETTINGS.equals(table) && (HOST_BOUND_SETTINGS.contains(column) || column.startsWith("acme_")
                || column.startsWith("pending_"))) {
            return true;
        }
        if ("peers".equals(table) && "private_key_pem".equals(column) && !includePrivateKeys) return true;
        return EXCLUDED_COLUMNS.getOrDefault(table, Set.of()).contains(column);
    }

    @Transactional
    public Map<String, List<Map<String, Object>>> dump(boolean includePrivateKeys) {
        em.flush();
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        em.unwrap(Session.class).doWork(c -> {
            for (String table : tables(c)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\"")) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            String col = md.getColumnName(i);
                            if (excludedColumn(table, col, includePrivateKeys)) continue;
                            Object v = rs.getObject(i);
                            if (v instanceof byte[] bytes) {
                                v = Map.of(BLOB_KEY, Base64.getEncoder().encodeToString(bytes));
                            }
                            row.put(col, v);
                        }
                        rows.add(row);
                    }
                }
                out.put(table, rows);
            }
        });
        return out;
    }

    /**
     * Replaces the content of every table the file carries; a table the file does
     * not mention is left as it is. Foreign keys are checked once, at commit, so
     * the order rows go in and out does not matter and the sites-and-peers cycle
     * needs no special care. A violation at commit fails the whole import, which
     * rolls back — the existing configuration survives a bad file.
     */
    @Transactional
    public void restore(Map<String, List<Map<String, Object>>> tables) {
        em.flush();
        em.unwrap(Session.class).doWork(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA defer_foreign_keys = ON");
            }
            List<String> present = new ArrayList<>();
            for (String table : tables(c)) {
                if (tables.get(table) != null) present.add(table);
            }
            // All deletes before any insert: a parent's delete cascades into its
            // children, and a child restored earlier would be swept away with it.
            for (String table : present) {
                if (SETTINGS.equals(table)) continue;
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("DELETE FROM \"" + table + "\"");
                }
            }
            for (String table : present) {
                Set<String> known = columns(c, table);
                if (SETTINGS.equals(table)) {
                    restoreSettings(c, tables.get(table), known);
                    continue;
                }
                for (Map<String, Object> row : tables.get(table)) insert(c, table, row, known);
            }
        });
        em.clear();
    }

    private static Set<String> columns(Connection c, String table) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (rs.next()) out.add(rs.getString("name"));
        }
        return out;
    }

    /** A column the file has and this schema no longer does is dropped; one the file lacks gets its default. */
    private void insert(Connection c, String table, Map<String, Object> row, Set<String> known) throws SQLException {
        List<String> cols = new ArrayList<>();
        for (String col : row.keySet()) {
            if (known.contains(col) && !excludedColumn(table, col, true)) cols.add(col);
        }
        if (cols.isEmpty()) return;
        StringBuilder sql = new StringBuilder("INSERT INTO \"").append(table).append("\" (");
        for (int i = 0; i < cols.size(); i++) sql.append(i == 0 ? "" : ", ").append('"').append(cols.get(i)).append('"');
        sql.append(") VALUES (").append("?, ".repeat(cols.size() - 1)).append("?)");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < cols.size(); i++) ps.setObject(i + 1, fromJson(row.get(cols.get(i))));
            ps.executeUpdate();
        }
    }

    private void restoreSettings(Connection c, List<Map<String, Object>> rows, Set<String> known) throws SQLException {
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.get(0);
        List<String> cols = new ArrayList<>();
        for (String col : row.keySet()) {
            if (known.contains(col) && !excludedColumn(SETTINGS, col, true)) cols.add(col);
        }
        if (cols.isEmpty()) return;
        StringBuilder sql = new StringBuilder("UPDATE settings SET updated_at = ?, updated_by = 'config-import'");
        for (String col : cols) sql.append(", \"").append(col).append("\" = ?");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setObject(1, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now()));
            for (int i = 0; i < cols.size(); i++) ps.setObject(i + 2, fromJson(row.get(cols.get(i))));
            ps.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private static Object fromJson(Object v) {
        if (v instanceof Map<?, ?> m && m.get(BLOB_KEY) instanceof String s) {
            return Base64.getDecoder().decode(s);
        }
        return v;
    }
}
