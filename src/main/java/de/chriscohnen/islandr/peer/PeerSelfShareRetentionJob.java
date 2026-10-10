package de.chriscohnen.islandr.peer;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Prunes {@link PeerSelfShare} rows that stopped being live (expired or
 * revoked) more than {@link #RETENTION_DAYS} ago. The share dialog shows
 * dead rows too — "Abgelaufen"/"Widerrufen", greyed out — rather than making
 * them disappear the moment they stop working, but nothing before this job
 * ever removed them, so the list only ever grew. Unlike activity data
 * ({@link ActivityRetentionJob}, Settings.activityRetentionDays), this isn't
 * exposed as a setting: the row carries no content beyond what
 * {@code peer.share_created}/{@code peer.share_revoked} already keep
 * permanently in the audit log, so there's no compliance reason to let an
 * admin tune it.
 */
@ApplicationScoped
public class PeerSelfShareRetentionJob {

    private static final Logger LOG = Logger.getLogger(PeerSelfShareRetentionJob.class);
    static final int RETENTION_DAYS = 30;

    @Scheduled(every = "24h",
               delayed = "5m",
               identity = "islandr-peer-self-share-retention",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void prune() {
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(RETENTION_DAYS));
        long deleted = PeerSelfShare.delete(
                "(revokedAt is not null and revokedAt < ?1) or (revokedAt is null and validUntil < ?2)",
                cutoff, cutoff);
        if (deleted > 0) LOG.debugf("peer self-share retention: pruned %d row(s) dead since before %s", deleted, cutoff);
    }
}
