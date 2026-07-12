package de.chriscohnen.islandr.acl;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeded "Everyone" auto-membership role (ADR-0013): created once, idempotent,
 * and empty of grants so it is inert until the admin deliberately grants something.
 */
@QuarkusTest
class RoleBootstrapTest {

    @Inject RoleBootstrap bootstrap;
    @Inject EntityManager em;

    @Test
    @Transactional
    void seedsExactlyOneEmptyEveryoneRoleWithAutoAll() {
        em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
        RoleResourceGrant.deleteAll();
        em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
        Role.deleteAll();

        bootstrap.seedEveryoneRole();
        bootstrap.seedEveryoneRole(); // must be idempotent

        List<Role> everyone = Role.list("name", RoleBootstrap.EVERYONE_ROLE_NAME);
        assertThat(everyone).hasSize(1);
        assertThat(everyone.get(0).autoAll).isTrue();
        // seeded empty — no implicit access until a grant is added deliberately
        assertThat(RoleResourceGrant.count("roleId", everyone.get(0).id)).isZero();
    }
}
