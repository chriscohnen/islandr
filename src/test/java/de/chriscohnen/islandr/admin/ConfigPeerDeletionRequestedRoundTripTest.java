package de.chriscohnen.islandr.admin;

import de.chriscohnen.islandr.peer.Peer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Peer.deletionRequestedAt survives export -> import (users-self-delete-peer)
 * — same round-trip discipline every other peer field already gets
 * (ConfigResourceMacRoundTripTest). Without this, restoring a backup would
 * silently resurrect a peer its owner had already asked to have removed.
 */
@QuarkusTest
class ConfigPeerDeletionRequestedRoundTripTest {

    @Inject ConfigService configService;

    @Test
    void deletionRequestedAt_survivesExportAndImport() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        String ip = "10.99.0.6";
        Instant requestedAt = Instant.now().minusSeconds(3600);

        QuarkusTransaction.requiringNew().run(() -> {
            Peer p = Peer.createNew(null, "PendingRemoval-" + suffix, publicKey, ip);
            p.enabled = false;
            p.deletionRequestedAt = requestedAt;
            p.persist();
        });

        ConfigExportDto.Export export =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        configService.importConfig(export);

        Peer imported = QuarkusTransaction.requiringNew()
                .call(() -> Peer.<Peer>find("assignedIp", ip).firstResult());
        assertThat(imported).isNotNull();
        assertThat(imported.deletionRequestedAt).isNotNull();
        assertThat(imported.enabled).isFalse();
    }

    /** The common case — no request pending — must not gain one from nowhere. */
    @Test
    void noDeletionRequest_staysNullAfterRoundTrip() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        String ip = "10.99.0.7";

        QuarkusTransaction.requiringNew().run(() -> {
            Peer p = Peer.createNew(null, "StillWanted-" + suffix, publicKey, ip);
            p.persist();
        });

        ConfigExportDto.Export export =
                QuarkusTransaction.requiringNew().call(() -> configService.export(false));

        configService.importConfig(export);

        Peer imported = QuarkusTransaction.requiringNew()
                .call(() -> Peer.<Peer>find("assignedIp", ip).firstResult());
        assertThat(imported).isNotNull();
        assertThat(imported.deletionRequestedAt).isNull();
    }
}
