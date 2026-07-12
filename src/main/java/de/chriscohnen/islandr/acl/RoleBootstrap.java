package de.chriscohnen.islandr.acl;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Seeds the default {@code Everyone} role on first startup (ADR-0013). It carries
 * the {@code autoAll} flag, so every user is an implicit member — present and
 * future — with no {@code user_roles} rows. It is created <em>empty</em> (no
 * grants), so it is inert until an admin deliberately grants something to it.
 *
 * <p>Idempotent and unconditional: unlike the {@code admin@local} seed, this does
 * not depend on the ENV bootstrap admin being enabled — the role is useful on any
 * install. Keyed on the {@code autoAll} flag, so exactly one such role can exist.
 */
@ApplicationScoped
public class RoleBootstrap {

    private static final Logger LOG = Logger.getLogger(RoleBootstrap.class);

    public static final String EVERYONE_ROLE_NAME = "Everyone";
    private static final String EVERYONE_DESCRIPTION =
            "Alle Nutzer — automatische Mitgliedschaft. Grants auf dieser Rolle erreichen jeden Nutzer (auch künftige).";

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        seedEveryoneRole();
    }

    /**
     * Create the auto-membership {@code Everyone} role if none exists yet. Returns
     * the existing or newly created row. Visible for tests.
     */
    @Transactional
    public Role seedEveryoneRole() {
        Role existing = Role.find("autoAll", true).firstResult();
        if (existing != null) {
            return existing;
        }
        Role r = Role.createNew(EVERYONE_ROLE_NAME, EVERYONE_DESCRIPTION);
        r.autoAll = true;
        r.persist();
        LOG.infof("seeded auto-membership role '%s'", EVERYONE_ROLE_NAME);
        return r;
    }
}
