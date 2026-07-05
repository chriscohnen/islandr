package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Fresh-install onboarding (design 2026-07-05, PRD F-01b): the ENV bootstrap
 * admin must have a usable {@code admin@local} User identity so it can own peers
 * and self-assign roles. The %test profile enables the ENV admin
 * (admin / test-admin-pw), so boot seeds the user and local login binds to it.
 */
@QuarkusTest
class AdminUserBootstrapTest {

    @Inject AdminUserBootstrap bootstrap;

    /** Local admin login resolves to the seeded admin@local user (userId non-null). */
    @Test
    void localAdminLogin_bindsToSeededAdminUser() {
        given().contentType("application/json")
                .body("{\"username\":\"admin\",\"password\":\"test-admin-pw\"}")
                .when().post("/api/v1/auth/login")
                .then().statusCode(200)
                .body("userId", notNullValue())
                .body("isAdmin", is(true));
    }

    /** Boot seeded exactly one admin@local (isAdmin); re-seeding creates no duplicate. */
    @Test
    void seedAdminUser_isIdempotent_andAdmin() {
        assertThat(User.<User>count("email", "admin@local")).isEqualTo(1L);
        User seeded = User.<User>find("email", "admin@local").firstResult();
        assertThat(seeded.isAdmin).isTrue();

        bootstrap.seedAdminUser("admin"); // second call must not duplicate
        assertThat(User.<User>count("email", "admin@local")).isEqualTo(1L);
    }
}
