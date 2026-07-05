package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.user.User;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Seeds a usable {@code admin@local} User for the ENV bootstrap admin on first
 * startup (PRD F-01b). The ENV admin ({@link AdminBootstrap}) is only an
 * in-memory credential with {@code userId=null}; without a User row it cannot own
 * a peer or be assigned roles. On boot, when the ENV admin is enabled and no
 * {@code admin@local} user exists, one is created (isAdmin). The local login then
 * binds its session to this user (see {@code AuthResource#login}).
 *
 * <p>Idempotent: only creates when missing, so subsequent boots and an operator
 * renaming {@code ISLANDR_ADMIN_USER} leave the existing row untouched.
 */
@ApplicationScoped
public class AdminUserBootstrap {

    private static final Logger LOG = Logger.getLogger(AdminUserBootstrap.class);

    /** Fixed placeholder email for the seeded bootstrap admin — never a real address. */
    public static final String ADMIN_EMAIL = "admin@local";

    @Inject AdminBootstrap adminBootstrap;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        if (!adminBootstrap.isEnabled()) {
            return; // no ENV admin configured — nothing to bind an identity to
        }
        seedAdminUser(adminBootstrap.userName());
    }

    /**
     * Create the {@code admin@local} administrator user if it does not exist yet.
     * Returns the existing or newly created row. Visible for tests.
     */
    @Transactional
    public User seedAdminUser(String name) {
        User existing = User.find("email", ADMIN_EMAIL).firstResult();
        if (existing != null) {
            return existing;
        }
        User u = User.createNew(name, ADMIN_EMAIL);
        u.isAdmin = true;
        u.persist();
        LOG.infof("seeded bootstrap admin user '%s' <%s>", name, ADMIN_EMAIL);
        return u;
    }
}
