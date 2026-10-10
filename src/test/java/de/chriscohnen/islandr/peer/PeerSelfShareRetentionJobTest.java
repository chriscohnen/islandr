package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Housekeeping for peer-self-share: nothing pruned a dead (expired or
 * revoked) row before this job, so "Deine Freigaben auf diesem Gerät" only
 * ever grew — see PeerSelfShareRetentionJob's own doc comment.
 */
@QuarkusTest
class PeerSelfShareRetentionJobTest {

    @Inject PeerSelfShareRetentionJob job;

    private final List<String> createdUserIds = new ArrayList<>();

    @AfterEach
    @Transactional
    void cleanup() {
        for (String userId : createdUserIds) {
            Peer.delete("userId", userId); // cascades peer_self_shares (V83)
            User.deleteById(userId);
        }
        createdUserIds.clear();
    }

    @Test
    @Transactional
    void prunesExpiredAndRevokedRowsOlderThanTheRetentionWindow() {
        String ownerPeerId = persistPeerForNewUser("retention-owner");
        String colleagueId = persistUser("retention-colleague").id;

        Instant now = Instant.now();
        Instant longDead = now.minus(PeerSelfShareRetentionJob.RETENTION_DAYS + 1, ChronoUnit.DAYS);
        Instant recentlyDead = now.minus(1, ChronoUnit.DAYS);

        String oldExpiredId = persistShare(ownerPeerId, colleagueId, 3000, longDead, null);
        String oldRevokedId = persistShare(ownerPeerId, colleagueId, 3001, now.plusSeconds(3600), longDead);
        String recentExpiredId = persistShare(ownerPeerId, colleagueId, 3002, recentlyDead, null);
        String liveId = persistShare(ownerPeerId, colleagueId, 3003, now.plusSeconds(3600), null);

        // Bulk delete doesn't auto-flush pending inserts to the same table —
        // without this, prune()'s JPQL delete runs against what's still only
        // in this transaction's persistence context, not visible to it yet.
        // Bulk delete doesn't auto-flush pending inserts to the same table,
        // and persist() keeps every row above managed in this session's
        // first-level cache regardless — see PeerTypeChangeTest#reload for
        // the same pattern: a findById after a bulk op within one
        // transaction hands back the pre-delete instance unless cleared.
        PeerSelfShare.getEntityManager().flush();
        job.prune();
        PeerSelfShare.getEntityManager().clear();

        assertThat(PeerSelfShare.<PeerSelfShare>findById(oldExpiredId)).isNull();
        assertThat(PeerSelfShare.<PeerSelfShare>findById(oldRevokedId)).isNull();
        assertThat(PeerSelfShare.<PeerSelfShare>findById(recentExpiredId)).isNotNull();
        assertThat(PeerSelfShare.<PeerSelfShare>findById(liveId)).isNotNull();
    }

    private String persistPeerForNewUser(String tag) {
        User u = persistUser(tag);
        byte[] keyBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(keyBytes);
        String publicKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        Peer p = Peer.createNew(u.id, tag + "-device", publicKey,
                "10.9.2." + (1 + (int) (Math.random() * 250)));
        p.persist();
        return p.id;
    }

    private User persistUser(String tag) {
        User u = User.createNew("Test " + tag, tag + "-" + UUID.randomUUID() + "@firma.de");
        u.isAdmin = false;
        u.persist();
        createdUserIds.add(u.id);
        return u;
    }

    private String persistShare(String ownerPeerId, String targetUserId, int port, Instant validUntil, Instant revokedAt) {
        PeerSelfShare s = PeerSelfShare.createNew(ownerPeerId, targetUserId, port, validUntil);
        s.revokedAt = revokedAt;
        s.persist();
        return s.id;
    }
}
