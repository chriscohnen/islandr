package de.chriscohnen.islandr.admin;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Repairs databases that a pre-0.12.0 config import corrupted.
 *
 * <p>Until 0.12.0 the import wrote {@code created_at} through native INSERTs using
 * {@code Instant.toString()}, i.e. ISO-8601 ({@code 2026-07-05T21:05:26.847Z}). SQLite is
 * dynamically typed and stored that verbatim, so the import reported success — but Hibernate
 * reads timestamps via {@code ResultSet.getTimestamp()}, and the SQLite driver parses only
 * {@code yyyy-MM-dd HH:mm:ss.SSS}. From then on every request touching an imported row failed
 * with "Error parsing time stamp": peers, users and avatars all returned HTTP 500, and the
 * instance was bricked by its own import.
 *
 * <p>{@link ConfigService} now writes the correct format, but rows already on disk stay broken,
 * and the operator cannot fix them from the app — reading them is precisely what fails. So this
 * runs once at startup and normalises them in place.
 *
 * <p>Why {@code strftime} and not a {@code REPLACE} of {@code T} and {@code Z}: the broken values
 * do not share one shape. {@code Instant.toString()} omits the fraction entirely at whole seconds
 * ({@code …:26Z}) and emits micro- or nanoseconds when it has them ({@code …:26.847123Z}), while
 * the driver insists on exactly three fractional digits. Stripping the separators would leave both
 * of those still unreadable. SQLite's {@code strftime} parses ISO-8601 including the {@code T} and
 * the {@code Z} and re-renders it as {@code SS.SSS} regardless of the input precision.
 *
 * <p>SQLite only, deliberately. Under PostgreSQL the column is a real timestamp type: the driver
 * parsed the ISO string correctly on insert and nothing was ever corrupted — and {@code strftime}
 * does not exist there. This is also why the repair is not a Flyway migration: a migration would
 * run blindly against both back-ends.
 *
 * <p>Idempotent: once repaired, no row matches the predicate again, so later boots are a no-op.
 */
@ApplicationScoped
public class ImportedTimestampRepair {

    private static final Logger LOG = Logger.getLogger(ImportedTimestampRepair.class);

    /** Every table whose {@code created_at} the config import writes with a native INSERT. */
    private static final List<String> TABLES = List.of(
            "users", "roles", "peers", "sites",
            "resources", "resource_ports", "port_groups", "role_resource_grants");

    @Inject
    EntityManager em;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url", defaultValue = "")
    String jdbcUrl;

    /**
     * Runs before every other {@code StartupEvent} observer. CDI gives observers of the same
     * event no defined order, and the other bootstraps ({@code FirewallBootstrap},
     * {@code RoleBootstrap}, {@code AdminUserBootstrap}, {@code ProxyReconciler}) all read the
     * very rows this repairs. Without an explicit priority the repair loses that race: the
     * firewall boot-apply reads a corrupted peer first, startup fails, and the repair never
     * runs at all — the instance crash-loops and cannot heal itself.
     */
    void onStart(@Observes @Priority(1) StartupEvent event) {
        if (!jdbcUrl.startsWith("jdbc:sqlite")) {
            return;
        }
        repair();
    }

    @Transactional
    void repair() {
        int repaired = 0;
        for (String table : TABLES) {
            // An ISO-8601 value is recognisable by its 'T' separator; a correctly written
            // value ("2026-07-05 21:05:26.847") never contains one.
            repaired += em.createNativeQuery(
                            "UPDATE " + table
                            + " SET created_at = strftime('%Y-%m-%d %H:%M:%f', created_at)"
                            + " WHERE created_at LIKE '%T%'")
                    .executeUpdate();
        }
        if (repaired > 0) {
            LOG.warnf("Repaired %d timestamp(s) written by a pre-0.12.0 config import "
                    + "(ISO-8601 instead of the format the SQLite driver reads back). "
                    + "Those rows were unreadable and returned HTTP 500 until now.", repaired);
        }
    }
}
