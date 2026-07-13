package de.chriscohnen.islandr.acl;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the state a pre-0.12.0 config import left behind, and proves the instance can
 * boot out of it again.
 *
 * <p>That export dropped {@code autoAll}, so the import re-created "Everyone" with the flag
 * cleared. {@code seedEveryoneRole} keys on the flag, found nothing, and tried to insert a
 * second "Everyone" — straight into the unique index over {@code roles.name}. The insert threw
 * during the startup transaction, so the application did not merely log an error: it failed to
 * start, and it failed again on every restart. The database could not be healed from inside the
 * app, because the app never got far enough to run.
 *
 * <p>Seeding therefore has to adopt a row that already holds the name rather than insert beside
 * it — which is the repair for every database an older import already damaged.
 */
@QuarkusTest
class RoleBootstrapAdoptionTest {

    @Inject
    RoleBootstrap roleBootstrap;

    @Inject
    EntityManager em;

    @Test
    void adoptsAnEveryoneRoleThatLostItsAutoAllFlag() {
        // Exactly what the broken import produced: the name is there, the flag is not.
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE roles SET auto_all = 0 WHERE name = ?1")
                        .setParameter(1, RoleBootstrap.EVERYONE_ROLE_NAME)
                        .executeUpdate());

        long autoAllBefore = QuarkusTransaction.requiringNew()
                .call(() -> Role.<Role>find("autoAll", true).count());
        assertThat(autoAllBefore)
                .as("precondition: no auto-membership role — this is what made seeding insert a duplicate")
                .isZero();

        // Before the fix this threw SQLITE_CONSTRAINT_UNIQUE and aborted startup.
        Role adopted = roleBootstrap.seedEveryoneRole();

        assertThat(adopted.autoAll)
                .as("the existing row must be adopted, not duplicated")
                .isTrue();

        assertThat(QuarkusTransaction.requiringNew()
                .call(() -> Role.<Role>find("name", RoleBootstrap.EVERYONE_ROLE_NAME).count()))
                .as("still exactly one 'Everyone' — a second one cannot exist, the index forbids it")
                .isEqualTo(1);

        assertThat(QuarkusTransaction.requiringNew()
                .call(() -> Role.<Role>find("autoAll", true).count()))
                .as("and it is the auto-membership role again")
                .isEqualTo(1);
    }
}
