package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.identity.FakeHttpFetcher;
import de.chriscohnen.islandr.webhook.WebhookDto;
import de.chriscohnen.islandr.webhook.WebhookEventType;
import de.chriscohnen.islandr.webhook.WebhookService;
import de.chriscohnen.islandr.webhook.WebhookTestCleanup;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** webhook-user-events: lifecycle events with the agreed payload, and none where nothing changed. */
@QuarkusTest
@org.junit.jupiter.api.extension.ExtendWith(de.chriscohnen.islandr.auth.AdminSessionExtension.class)
class UserWebhookEventsTest {

    @Inject WebhookService webhooks;
    @Inject de.chriscohnen.islandr.apikey.ApiKeyService apiKeys;
    @Inject FakeHttpFetcher http;

    private String url;

    @BeforeEach
    void setUp() {
        WebhookTestCleanup.deleteAllWebhooks();
        QuarkusTransaction.requiringNew().run(() -> {
            http.reset();
            url = "https://hook.example.com/users-" + System.nanoTime();
            webhooks.create(new WebhookDto.CreateRequest(url, null,
                    List.of(WebhookEventType.USER_CREATED, WebhookEventType.USER_UPDATED,
                            WebhookEventType.USER_ENABLED, WebhookEventType.USER_DISABLED,
                            WebhookEventType.USER_DELETED), null, null, null, null), "admin");
            http.postBodyStub(url, 200, "ok", null);
        });
    }

    @AfterEach
    void tearDown() {
        WebhookTestCleanup.deleteAllWebhooks();
    }

    /** A request that SQLite's shared cache refused with a lock error (500) is rolled back whole,
     *  so repeating it is safe; background work elsewhere in the suite can hold a table at that instant. */
    private static io.restassured.response.Response send(
            java.util.function.Supplier<io.restassured.response.Response> call) {
        io.restassured.response.Response r = call.get();
        for (int i = 0; i < 5 && r.statusCode() == 500; i++) {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            r = call.get();
        }
        return r;
    }

    private List<String> bodies() {
        return http.calls.stream().filter(c -> c.url().equals(url)).map(c -> c.rawBodyText()).toList();
    }

    private void awaitDeliveries(int n) {
        BooleanSupplier ok = () -> bodies().size() >= n;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !ok.getAsBoolean()) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        // The delivery is recorded (an UPDATE on webhooks) just after the POST returns; the next
        // request of the test writes the audit log. Let the recording finish first, or SQLite's
        // shared cache answers one of them with SQLITE_LOCKED.
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    void lifecycle_firesOneEventEach_withPayloadAndWithoutSecrets() throws Exception {
        String email = "hook-" + System.nanoTime() + "@example.com";
        String id = send(() -> given().contentType("application/json")
                .body("{\"name\":\"Hook User\",\"email\":\"" + email + "\"}")
                .post("/api/v1/users")).then().statusCode(201).extract().path("id");
        awaitDeliveries(1);
        assertThat(bodies()).hasSize(1);
        String created = bodies().get(0);
        assertThat(created).contains("\"user.created\"").contains(id).contains(email)
                .contains("\"source\":\"local\"").contains("\"roleIds\":[]").contains("\"enabled\":true")
                .doesNotContain("passwordHash").doesNotContain("password");

        // unchanged update = no event; a rename = one, naming the field
        send(() -> given().contentType("application/json")
                .body("{\"name\":\"Hook User\",\"email\":\"" + email + "\"}")
                .put("/api/v1/users/" + id)).then().statusCode(200);
        Thread.sleep(300);
        assertThat(bodies()).hasSize(1);
        send(() -> given().contentType("application/json")
                .body("{\"name\":\"Renamed\",\"email\":\"" + email + "\"}")
                .put("/api/v1/users/" + id)).then().statusCode(200);
        awaitDeliveries(2);
        assertThat(bodies().get(1)).contains("\"user.updated\"").contains("\"changedFields\":[\"name\"]");

        send(() -> given().contentType("application/json").body("{\"enabled\":false}")
                .put("/api/v1/users/" + id + "/enabled")).then().statusCode(200);
        awaitDeliveries(3);
        assertThat(bodies().get(2)).contains("\"user.disabled\"").contains("\"enabled\":false");

        send(() -> given().contentType("application/json").body("{\"enabled\":true}")
                .put("/api/v1/users/" + id + "/enabled")).then().statusCode(200);
        awaitDeliveries(4);
        assertThat(bodies().get(3)).contains("\"user.enabled\"");

        send(() -> given().delete("/api/v1/users/" + id)).then().statusCode(204);
        awaitDeliveries(5);
        assertThat(bodies().get(4)).contains("\"user.deleted\"").contains(email);
        assertThat(bodies()).hasSize(5);
    }

    @Test
    void googleImport_ofThreeUsers_firesThreeEvents_andARepeatNone() throws Exception {
        long n = System.nanoTime();
        String body = "{\"emails\":[\"a" + n + "@example.com\",\"b" + n + "@example.com\",\"c" + n + "@example.com\"]}";
        send(() -> given().contentType("application/json").body(body)
                .post("/api/v1/users/import/google")).then().statusCode(200);
        awaitDeliveries(3);
        assertThat(bodies()).hasSize(3).allMatch(b -> b.contains("\"user.created\"") && b.contains("\"source\":\"import\""));

        send(() -> given().contentType("application/json").body(body)
                .post("/api/v1/users/import/google")).then().statusCode(200);
        Thread.sleep(300);
        assertThat(bodies()).hasSize(3);
    }

    @Test
    void externalApi_disable_firesWithSourceApi() throws Exception {
        String key = apiKeys.create("hook", java.util.Set.of(de.chriscohnen.islandr.apikey.ApiKeyScope.FULL), "admin").rawKey();
        String id = send(() -> given().contentType("application/json")
                .body("{\"name\":\"Api User\",\"email\":\"api-" + System.nanoTime() + "@example.com\"}")
                .post("/api/v1/users")).then().statusCode(201).extract().path("id");
        awaitDeliveries(1);

        send(() -> given().header("Authorization", "Bearer " + key).contentType("application/json")
                .body("{\"enabled\":false}")
                .put("/api/external/v1/users/" + id + "/enabled")).then().statusCode(200);
        awaitDeliveries(2);
        assertThat(bodies().get(1)).contains("\"user.disabled\"").contains("\"source\":\"api\"");
    }
}
