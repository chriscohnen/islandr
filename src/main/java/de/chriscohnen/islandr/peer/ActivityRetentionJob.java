package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Prunes {@link PeerDailyActivity} rows older than the configured retention
 * window ({@code Settings.activityRetentionDays}, default 180 — see #32).
 * Runs once a day; there is no live-data urgency here, unlike {@link ActivityPoller}.
 */
@ApplicationScoped
public class ActivityRetentionJob {

    private static final Logger LOG = Logger.getLogger(ActivityRetentionJob.class);

    @ConfigProperty(name = "islandr.activity.poll-enabled", defaultValue = "true")
    boolean enabled;

    @Inject SettingsService settings;

    @Scheduled(every = "24h",
               delayed = "5m",
               identity = "islandr-activity-retention",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void prune() {
        if (!enabled) return;
        int retentionDays = settings.get().activityRetentionDays;
        String cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays).toString();
        long deleted = PeerDailyActivity.delete("id.day < ?1", cutoff);
        if (deleted > 0) LOG.debugf("activity retention: pruned %d row(s) older than %s", deleted, cutoff);
    }
}
