package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.audit.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #80: a failed login was recorded as a username and nothing else —
 * "who tried" without "from where", which is half a record and gives an
 * external blocker nothing to act on.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class LoginFailureRecordTest {

    @Transactional
    AuditLog lastFailureFor(String username) {
        return AuditLog.find("actor = ?1 and action = ?2 order by createdAt desc",
                username, "auth.login_failed").firstResult();
    }

    @Test
    void aFailedLoginRecordsTheClientAddress() {
        String username = "ghost-" + UUID.randomUUID() + "@firma.de";
        given().contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"not-the-password\"}")
                .when().post("/api/v1/auth/login")
                .then().statusCode(401);

        AuditLog row = lastFailureFor(username);
        assertThat(row).as("the attempt must be audited").isNotNull();
        assertThat(row.metaJson).contains("clientIp");
        // Loopback, because the test talks to the hub directly — the point is
        // that an address is recorded at all, not which one.
        assertThat(row.metaJson).contains("127.0.0.1");
    }

    /**
     * The delay must not become a user-enumeration oracle: an account that
     * does not exist has to be refused exactly like one that does.
     */
    @Test
    void anUnknownAccountIsRefusedTheSameWayAKnownOneIs() {
        String known = "known-" + UUID.randomUUID() + "@firma.de";
        String uid = given().contentType("application/json")
                .body("{\"name\":\"Known\",\"email\":\"" + known + "\"}")
                .when().post("/api/v1/users").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body("{\"password\":\"right-pw-value\"}")
                .when().put("/api/v1/users/" + uid + "/password").then().statusCode(200);

        String knownBody = given().contentType("application/json")
                .body("{\"username\":\"" + known + "\",\"password\":\"wrong-pw-value\"}")
                .when().post("/api/v1/auth/login")
                .then().statusCode(401).extract().asString();

        String unknownBody = given().contentType("application/json")
                .body("{\"username\":\"nobody-" + UUID.randomUUID() + "@firma.de\","
                        + "\"password\":\"wrong-pw-value\"}")
                .when().post("/api/v1/auth/login")
                .then().statusCode(401).extract().asString();

        assertThat(unknownBody).isEqualTo(knownBody);
    }
}
