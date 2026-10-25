package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.audit.AuditLog;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.auth.SessionService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;

/**
 * oidc-unlink: an admin can release an account's single sign-on link.
 *
 * <p>Since oidc-email-link-verified a login never re-points an account that
 * is already linked — that re-pointing was how an unconfirmed address took
 * one over. It was also how a switch of identity provider used to carry
 * every account across on its own. Without it, an admin needs a way to say
 * "this account may be linked afresh": after the unlink, the next login on a
 * confirmed address links it like a local account.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class UserOidcUnlinkTest {

    @Inject SessionService sessions;

    @Transactional
    String linkedUser(String provider, String subject) {
        User u = User.createNew("Linked " + subject, "unlink-" + UUID.randomUUID() + "@firma.de");
        u.oidcProvider = provider;
        u.oidcSubject = subject;
        u.persist();
        return u.id;
    }

    @Transactional
    User reload(String id) {
        return User.findById(id);
    }

    @Test
    void anAdminUnlinks_andTheAccountCarriesNoLinkAnyMore() {
        String id = linkedUser("google", "google-sub-" + UUID.randomUUID());

        given().when().delete("/api/v1/users/" + id + "/oidc-link")
                .then().statusCode(200)
                .body("oidcProvider", nullValue());

        User u = reload(id);
        assertThat(u.oidcProvider).isNull();
        assertThat(u.oidcSubject).isNull();
        assertThat(u.oidcCustomProviderId).isNull();
    }

    /** Who removed whose link, and which it was — the audit has to answer it. */
    @Test
    void theUnlinkIsAudited() {
        String id = linkedUser("google", "google-sub-audit-" + UUID.randomUUID());

        given().when().delete("/api/v1/users/" + id + "/oidc-link").then().statusCode(200);

        assertThat(auditCount("user.oidc_unlink", id)).isEqualTo(1);
    }

    @Transactional
    long auditCount(String action, String userId) {
        return AuditLog.count("action = ?1 and target like ?2", action, "%" + userId + "%");
    }

    @Test
    void unlinkingAnUnlinkedAccountChangesNothing() {
        String id = given().contentType("application/json")
                .body("{\"name\":\"Local\",\"email\":\"unlink-local-" + UUID.randomUUID() + "@firma.de\"}")
                .when().post("/api/v1/users").then().statusCode(201).extract().path("id");

        given().when().delete("/api/v1/users/" + id + "/oidc-link").then().statusCode(200);

        assertThat(auditCount("user.oidc_unlink", id)).isZero();
    }

    @Test
    void anUnknownUserIs404() {
        given().when().delete("/api/v1/users/no-such-user/oidc-link").then().statusCode(404);
    }

    @Test
    void aNonAdminCannotUnlink() {
        String target = linkedUser("google", "google-sub-protected-" + UUID.randomUUID());
        String self = linkedUser("google", "google-sub-self-" + UUID.randomUUID());
        Session s = sessions.create(Session.GOOGLE, "principal-" + self.substring(0, 6), self);
        RestAssured.requestSpecification = null;

        given().cookie(SessionFilter.COOKIE_NAME, s.id)
                .when().delete("/api/v1/users/" + target + "/oidc-link")
                .then().statusCode(403);

        assertThat(reload(target).oidcSubject).isNotNull();
    }
}
