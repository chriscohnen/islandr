package de.chriscohnen.islandr.acl;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the schema-level property {@code db.migration.V80__resource_type_open_set}
 * exists for, against the app's own boot — not a standalone Flyway harness.
 * This test's Flyway execution goes through the exact wiring Quarkus builds
 * for the running app (the build-time discovery path that also has to work
 * in a native image), so a novel type inserting cleanly here is evidence the
 * Java migration was found and ran through that path, not only through
 * Flyway's own generic classpath scanner (which
 * {@code V80ResourceTypeOpenSetTest} already covers in isolation).
 *
 * <p>Goes straight to the entity, bypassing {@code ResourceDto}'s
 * {@code @Pattern} — that regex still gates what the API accepts today
 * (widening it is its own follow-up task per {@code loop/TASK.md}). What
 * this proves is narrower and is the whole point of the migration: the
 * database itself no longer rejects a value the regex doesn't yet know
 * about.
 */
@QuarkusTest
class ResourceTypeOpenSetTest {

    @Test
    void aTypeTheOldCheckConstraintWouldHaveRejectedPersistsCleanly() {
        String resourceId = QuarkusTransaction.requiringNew().call(() -> {
            Site site = Site.createNew("open-set-" + UUID.randomUUID(), "10.77.0.0/29", null);
            site.persist();

            Resource r = new Resource();
            r.id = UUID.randomUUID().toString();
            r.siteId = site.id;
            r.name = "AP-1";
            r.ip = "10.77.0.5";
            // Never a valid value under the CHECK constraint V13/V39 shipped
            // (computer/router/printer/nas/camera/iot/virt-host/rackserver/
            // kvm/management/other) — this would have thrown
            // SQLITE_CONSTRAINT_CHECK before V80.
            r.type = "accesspoint";
            r.createdAt = Instant.now();
            r.persist();
            return r.id;
        });

        Resource reloaded = QuarkusTransaction.requiringNew().call(() -> Resource.findById(resourceId));
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.type).isEqualTo("accesspoint");
    }
}
