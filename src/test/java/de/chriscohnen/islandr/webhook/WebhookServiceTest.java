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
                        List.of(WebhookEventType.PEER_CONNECTED)), "admin");

        assertThat(r.plaintextSecret()).isNotBlank();
        assertThat(r.webhook().enabled).isTrue();
        assertThat(r.webhook().eventTypeSet()).containsExactly(WebhookEventType.PEER_CONNECTED);
        assertThat(r.webhook().secret).isEqualTo(r.plaintextSecret());
    }

    @Test
    void create_rejectsUnknownEventType() {
        assertThatThrownBy(() -> svc.create(
                new WebhookDto.CreateRequest("https://example.com/hook", null, List.of("not.a.real.event")), "admin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unknown event type");
    }

    @Test
    void create_rejectsNonHttpUrl() {
        assertThatThrownBy(() -> svc.create(
                new WebhookDto.CreateRequest("ftp://example.com/hook", null, List.of()), "admin"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void update_changesFilterAndRotatesUrl() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://a.example.com", null,
                List.of(WebhookEventType.PEER_CONNECTED)), "admin").webhook();

        Webhook updated = svc.update(w.id, new WebhookDto.UpdateRequest(
                "https://b.example.com", "renamed",
                List.of(WebhookEventType.ACL_GRANT_CREATED, WebhookEventType.ACL_GRANT_REVOKED), false), "admin");

        assertThat(updated.url).isEqualTo("https://b.example.com");
        assertThat(updated.description).isEqualTo("renamed");
        assertThat(updated.enabled).isFalse();
        assertThat(updated.eventTypeSet()).containsExactlyInAnyOrder(
                WebhookEventType.ACL_GRANT_CREATED, WebhookEventType.ACL_GRANT_REVOKED);
    }

    @Test
    void rotateSecret_changesSecretValue() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://a.example.com", null, List.of()), "admin").webhook();
        String original = w.secret;

        String rotated = svc.rotateSecret(w.id, "admin");

        assertThat(rotated).isNotEqualTo(original);
        assertThat(svc.get(w.id).secret).isEqualTo(rotated);
    }

    @Test
    void delete_removesIt() {
        Webhook w = svc.create(new WebhookDto.CreateRequest("https://a.example.com", null, List.of()), "admin").webhook();
        svc.delete(w.id);
        assertThatThrownBy(() -> svc.get(w.id)).isInstanceOf(NotFoundException.class);
    }
}
