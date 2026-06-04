package de.chriscohnen.islandr.audit;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/v1/audit: cursor pagination + actor/action filters.
 * Authorisation matrix is covered in AuthorizationMatrixTest; this file
 * focuses on the read-side semantics.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class AuditResourceTest {

    @Inject AuditService audit;

    @BeforeEach
    @Transactional
    void wipeAndSeed() {
        AuditLog.deleteAll();
        // Seed in chronological order so the most recently written has the
        // highest createdAt — that's what the resource returns first.
        audit.logEvent("alice@firma.de", "peer.create",   "Peer:p1", Map.of());
        audit.logEvent("bob@firma.de",   "peer.delete",   "Peer:p1", Map.of());
        audit.logEvent("alice@firma.de", "user.create",   "User:u1", Map.of());
        audit.logEvent("admin",          "settings.update", "Settings:singleton", Map.of());
    }

    @Test
    void list_returnsAllRows_orderedByCreatedAtDescending() {
        JsonPath body = given().when().get("/api/v1/audit").then().statusCode(200).extract().jsonPath();
        List<String> actions = body.getList("action");
        List<String> created = body.getList("createdAt");
        assertThat(actions).containsExactlyInAnyOrder(
                "peer.create", "peer.delete", "user.create", "settings.update");
        // The list must be sorted descending by createdAt. We can't assert
        // an exact order of seed events because they're often within the
        // same wall-clock millisecond and UUIDs break ties unpredictably.
        for (int i = 1; i < created.size(); i++) {
            assertThat(created.get(i - 1)).isGreaterThanOrEqualTo(created.get(i));
        }
    }

    @Test
    void list_filterByActor() {
        List<String> actions = given().queryParam("actor", "alice@firma.de")
                .when().get("/api/v1/audit").then().statusCode(200)
                .extract().jsonPath().getList("action");
        assertThat(actions).containsExactlyInAnyOrder("peer.create", "user.create");
    }

    @Test
    void list_filterByAction() {
        List<String> actors = given().queryParam("action", "peer.create")
                .when().get("/api/v1/audit").then().statusCode(200)
                .extract().jsonPath().getList("actor");
        assertThat(actors).containsExactly("alice@firma.de");
    }

    @Test
    void list_limit_capsRows() {
        List<String> actions = given().queryParam("limit", 2)
                .when().get("/api/v1/audit").then().statusCode(200)
                .extract().jsonPath().getList("action");
        assertThat(actions).hasSize(2);
    }

    @Test
    void list_beforeCursor_returnsOlderRowsOnly() {
        // Grab the oldest entry on page 1 (with limit=2) so we can paginate past it.
        JsonPath p1 = given().queryParam("limit", 2)
                .when().get("/api/v1/audit").then().statusCode(200).extract().jsonPath();
        List<String> p1Created = p1.getList("createdAt");
        String oldestOnPage1 = p1Created.get(p1Created.size() - 1);
        // Page 2 must NOT contain anything newer-or-equal than that cursor.
        JsonPath p2 = given().queryParam("before", oldestOnPage1).queryParam("limit", 10)
                .when().get("/api/v1/audit").then().statusCode(200).extract().jsonPath();
        List<String> p2Created = p2.getList("createdAt");
        for (String t : p2Created) {
            assertThat(t).isLessThan(oldestOnPage1);
        }
    }

    @Test
    void list_invalidCursor_returns400() {
        given().queryParam("before", "not-a-timestamp")
                .when().get("/api/v1/audit").then().statusCode(400);
    }
}
