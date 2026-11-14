package de.chriscohnen.islandr.backup;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.webhook.WebhookEventType;
import de.chriscohnen.islandr.webhook.WebhookDispatcher;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;

/**
 * Fires the automatic backup (backup-schedule). Checks once a minute whether
 * the schedule says one is due ({@link BackupSchedule}); the work is the same
 * {@link BackupService#run} an admin's click uses, so the two cannot overlap.
 * A failed run is recorded on the page, written to the audit log and sent as a
 * {@code backup.failed} webhook — an unattended job that fails quietly is a
 * backup nobody has.
 */
@ApplicationScoped
public class BackupScheduler {

    private static final Logger LOG = Logger.getLogger(BackupScheduler.class);

    @ConfigProperty(name = "islandr.backup.schedule.enabled", defaultValue = "true")
    boolean enabled;

    @Inject BackupService backups;
    @Inject AuditService audit;
    @Inject WebhookDispatcher webhooks;

    @Scheduled(every = "60s", identity = "islandr-backup-schedule",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        if (!enabled) return;
        try {
            runIfDue(Instant.now());
        } catch (RuntimeException e) {
            LOG.warnf("backup schedule tick failed: %s", e.getMessage());
        }
    }

    /** Package-private so a test can drive it without waiting for the timer. */
    void runIfDue(Instant now) {
        if (!backups.scheduledBackupDue(now)) return;
        try {
            BackupDto.RunResult r = backups.run();
            audit.logEvent("system", "backup.run", "Backup:" + r.name(), Map.of("size", r.size(), "scheduled", true));
        } catch (RuntimeException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            audit.logEvent("system", "backup.failed", "Backup:scheduled", Map.of("error", why));
            webhooks.publish(WebhookEventType.BACKUP_FAILED, "system", "Backup:scheduled", Map.<String, Object>of("error", why));
        }
    }
}
