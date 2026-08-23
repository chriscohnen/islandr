package de.chriscohnen.islandr.webhook;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class WebhookServiceTest {

    @Inject WebhookService svc;

    @BeforeEach
    @Transactional
    void reset() {
        Webhook.deleteAll();
    }

    @Test
    void create_generatesSecretAndPersistsDisabledFilter() {
        WebhookService.CreateResult r = svc.create(
                new WebhookDto.CreateRequest("https://example.com/hook", "test hook",
                        List.of(WebhookEventType.PEER_CONNECTED), null, null, null, null), "admin");

        assertThat(r.plaintextSecret()).isNotBlank();
        assertThat(r.webhook().enabled).isTrue();
        assertThat(r.webhook().eventTypeSet()).containsExactly(WebhookEventType.PEER_CONNECTED);
        assertThat(r.webhook().secret).isEqualTo(r.plaintextSecret());
    }

    @Test
    void create_rejectsUnknownEventType() {
        assertThatThrownBy(() -> svc.create(
                new WebhookDto.CreateRequest("https://example.com/hook", null, List.of("not.a.real.event"), null, null, null, null), "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unknown event type");
    }

    @Test
    void create_rejectsNonHttpUrl() {
        assertThatThrownBy(() -> svc.create(
                new WebhookDto.CreateRequest("ftp://example.com/hook", null, List.of(), null, null, null, null), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void update_changesFilterAndRotatesUrl() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://a.example.com", null,
                List.of(WebhookEventType.PEER_CONNECTED), null, null, null, null), "admin").webhook();

        Webhook updated = svc.update(w.id, new WebhookDto.UpdateRequest(
                "https://b.example.com", "renamed",
                List.of(WebhookEventType.ACL_GRANT_CREATED, WebhookEventType.ACL_GRANT_REVOKED), false, null, null, null, null), "admin");

        assertThat(updated.url).isEqualTo("https://b.example.com");
        assertThat(updated.description).isEqualTo("renamed");
        assertThat(updated.enabled).isFalse();
        assertThat(updated.eventTypeSet()).containsExactlyInAnyOrder(
                WebhookEventType.ACL_GRANT_CREATED, WebhookEventType.ACL_GRANT_REVOKED);
    }

    @Test
    void rotateSecret_changesSecretValue() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://a.example.com", null, List.of(), null, null, null, null), "admin").webhook();
        String original = w.secret;

        String rotated = svc.rotateSecret(w.id, "admin");

        assertThat(rotated).isNotEqualTo(original);
        assertThat(svc.get(w.id).secret).isEqualTo(rotated);
    }

    @Test
    void create_gotify_requiresAdminSuppliedToken() {
        assertThatThrownBy(() -> svc.create(new WebhookDto.CreateRequest(
                "https://gotify.example.com", null, List.of(WebhookEventType.PEER_CONNECTED),
                WebhookFormat.GOTIFY, null, null, null), "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("app token");
    }

    @Test
    void create_gotify_storesAdminSuppliedTokenAsSecret() {
        WebhookService.CreateResult r = svc.create(new WebhookDto.CreateRequest(
                "https://gotify.example.com", null, List.of(WebhookEventType.PEER_CONNECTED),
                WebhookFormat.GOTIFY, "my-app-token", null, null), "admin");

        assertThat(r.webhook().format).isEqualTo(WebhookFormat.GOTIFY);
        assertThat(r.webhook().secret).isEqualTo("my-app-token");
        assertThat(r.plaintextSecret()).isEqualTo("my-app-token");
    }

    @Test
    void rotateSecret_onGotifyWebhook_rejected() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://gotify.example.com", null,
                List.of(), WebhookFormat.GOTIFY, "token-1", null, null), "admin").webhook();

        assertThatThrownBy(() -> svc.rotateSecret(w.id, "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Gotify");
    }

    @Test
    void update_gotify_canChangeToken() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://gotify.example.com", null,
                List.of(), WebhookFormat.GOTIFY, "token-1", null, null), "admin").webhook();

        Webhook updated = svc.update(w.id,
                new WebhookDto.UpdateRequest(null, null, null, null, null, "token-2", null, null), "admin");

        assertThat(updated.secret).isEqualTo("token-2");
    }

    @Test
    void create_unknownFormat_rejected() {
        assertThatThrownBy(() -> svc.create(new WebhookDto.CreateRequest(
                "https://example.com", null, List.of(), "carrier-pigeon", null, null, null), "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unknown format");
    }

    @Test
    void create_withHeaderNameAndValue_persistsBoth() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://a.example.com", null, List.of(),
                null, null, "Authorization", "Bearer secret-token"), "admin").webhook();

        assertThat(w.extraHeaderName).isEqualTo("Authorization");
        assertThat(w.extraHeaderValue).isEqualTo("Bearer secret-token");
    }

    @Test
    void create_headerNameWithoutValue_rejected() {
        assertThatThrownBy(() -> svc.create(new WebhookDto.CreateRequest("https://a.example.com", null,
                List.of(), null, null, "X-API-Key", null), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_headerValueWithoutName_rejected() {
        assertThatThrownBy(() -> svc.create(new WebhookDto.CreateRequest("https://a.example.com", null,
                List.of(), null, null, null, "some-value"), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void update_blankHeaderName_clearsTheWholeHeader() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://a.example.com", null, List.of(),
                null, null, "X-API-Key", "k-1"), "admin").webhook();

        Webhook updated = svc.update(w.id,
                new WebhookDto.UpdateRequest(null, null, null, null, null, null, "", null), "admin");

        assertThat(updated.extraHeaderName).isNull();
        assertThat(updated.extraHeaderValue).isNull();
    }

    @Test
    void update_blankHeaderValue_leavesExistingValueUnchanged() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://a.example.com", null, List.of(),
                null, null, "X-API-Key", "k-1"), "admin").webhook();

        // Renaming the header without resupplying the value (same "blank = no
        // change" convention the secret field already uses) must keep k-1.
        Webhook updated = svc.update(w.id,
                new WebhookDto.UpdateRequest(null, null, null, null, null, null, "X-Api-Key-2", ""), "admin");

        assertThat(updated.extraHeaderName).isEqualTo("X-Api-Key-2");
        assertThat(updated.extraHeaderValue).isEqualTo("k-1");
    }

    @Test
    void delete_removesIt() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://a.example.com", null, List.of(), null, null, null, null), "admin").webhook();
        svc.delete(w.id);
        assertThatThrownBy(() -> svc.get(w.id)).isInstanceOf(NotFoundException.class);
    }
}
