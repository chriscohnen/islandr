package de.chriscohnen.islandr.webhook;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.identity.FakeHttpFetcher;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/** REST-level tests for the outgoing-webhooks admin API (issue #68). */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class WebhookResourceTest {

    @Inject FakeHttpFetcher http;

    @BeforeEach
    void reset() {
        http.reset();
        deleteAll();
    }

    @Test
    void eventTypes_listsCanonicalKeys() {
        given().when().get("/api/v1/webhooks/event-types")
                .then().statusCode(200)
                .body("$", hasSize(WebhookEventType.ALL.size()))
                .body("$", org.hamcrest.Matchers.hasItem("peer.connected"));
    }

    @Test
    void create_returnsSecretOnceThenListDoesNot() {
        String id = given().contentType("application/json").body("""
                { "url": "https://hook.example.com/x", "description": "d",
                  "eventTypes": ["peer.connected", "acl.grant_created"] }
                """)
                .when().post("/api/v1/webhooks")
                .then().statusCode(200)
                .body("secret", notNullValue())
                .body("webhook.id", notNullValue())
                .extract().path("webhook.id");

        given().when().get("/api/v1/webhooks")
                .then().statusCode(200).body("$", hasSize(1))
                .body("[0].id", is(id))
                .body("[0].secret", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void update_changesFilterAndEnabled() {
        String id = createHook();

        given().contentType("application/json").body("""
                { "eventTypes": ["discovery.scan_completed"], "enabled": false }
                """)
                .when().put("/api/v1/webhooks/" + id)
                .then().statusCode(200)
                .body("enabled", is(false))
                .body("eventTypes", hasSize(1))
                .body("eventTypes[0]", is("discovery.scan_completed"));
    }

    @Test
    void rotateSecret_returnsNewSecretOnce() {
        String id = createHook();
        given().contentType("application/json").when().post("/api/v1/webhooks/" + id + "/rotate-secret")
                .then().statusCode(200).body("secret", notNullValue());
    }

    @Test
    void test_firesRegardlessOfFilter() {
        String id = createHookWithUrl("https://hook.example.com/testfire");
        http.postBodyStub("https://hook.example.com/testfire", 200, "ok", null);

        given().contentType("application/json").when().post("/api/v1/webhooks/" + id + "/test")
                .then().statusCode(200)
                .body("success", is(true));
    }

    @Test
    void delete_removesIt() {
        String id = createHook();
        given().when().delete("/api/v1/webhooks/" + id).then().statusCode(204);
        given().when().get("/api/v1/webhooks/" + id).then().statusCode(404);
    }

    @Test
    void create_unknownEventType_returns400() {
        given().contentType("application/json").body("""
                { "url": "https://hook.example.com/x", "eventTypes": ["nope"] }
                """)
                .when().post("/api/v1/webhooks")
                .then().statusCode(400);
    }

    // -- helpers --------------------------------------------------------

    private String createHook() {
        return createHookWithUrl("https://hook.example.com/" + java.util.UUID.randomUUID());
    }

    private String createHookWithUrl(String url) {
        return given().contentType("application/json").body("""
                { "url": "%s", "eventTypes": ["peer.connected"] }
                """.formatted(url))
                .when().post("/api/v1/webhooks")
                .then().statusCode(200)
                .extract().path("webhook.id");
    }

    @Transactional
    void deleteAll() {
        for (Webhook w : Webhook.<Webhook>listAll()) w.delete();
    }
}
