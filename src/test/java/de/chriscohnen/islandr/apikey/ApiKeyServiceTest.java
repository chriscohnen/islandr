package de.chriscohnen.islandr.apikey;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class ApiKeyServiceTest {

    @Inject ApiKeyService svc;

    @BeforeEach
    @Transactional
    void reset() {
        ApiKey.deleteAll();
    }

    @Test
    void create_generatesPrefixedRawKeyAndStoresOnlyHash() {
        ApiKeyService.CreateResult r = svc.create("ci-script", "admin");

        assertThat(r.rawKey()).startsWith("islandr_");
        assertThat(r.apiKey().keyHash).isNotEqualTo(r.rawKey());
        assertThat(r.apiKey().keyPrefix).isEqualTo(r.rawKey().substring(0, r.apiKey().keyPrefix.length()));
        assertThat(r.apiKey().isActive()).isTrue();
    }

    @Test
    void authenticate_validKey_returnsItAndBumpsLastUsed() {
        ApiKeyService.CreateResult r = svc.create("ci-script", "admin");
        assertThat(readKey(r.apiKey().id).lastUsedAt).isNull();

        ApiKey authenticated = svc.authenticate(r.rawKey());

        assertThat(authenticated).isNotNull();
        assertThat(authenticated.id).isEqualTo(r.apiKey().id);
        assertThat(readKey(r.apiKey().id).lastUsedAt).isNotNull();
    }

    @Test
    void authenticate_unknownKey_returnsNull() {
        assertThat(svc.authenticate("islandr_not-a-real-key")).isNull();
    }

    @Test
    void authenticate_blankKey_returnsNull() {
        assertThat(svc.authenticate("")).isNull();
        assertThat(svc.authenticate(null)).isNull();
    }

    @Test
    void authenticate_revokedKey_returnsNull() {
        ApiKeyService.CreateResult r = svc.create("ci-script", "admin");
        svc.revoke(r.apiKey().id, "admin");

        assertThat(svc.authenticate(r.rawKey())).isNull();
    }

    @Test
    void revoke_isIdempotent() {
        ApiKeyService.CreateResult r = svc.create("ci-script", "admin");
        svc.revoke(r.apiKey().id, "admin");
        svc.revoke(r.apiKey().id, "admin"); // second call must not throw

        assertThat(readKey(r.apiKey().id).isActive()).isFalse();
    }

    @Test
    void get_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> svc.get("does-not-exist")).isInstanceOf(NotFoundException.class);
    }

    @Transactional
    ApiKey readKey(String id) {
        return ApiKey.findById(id);
    }
}
