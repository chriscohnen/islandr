package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.webhook.WebhookDispatcher;
import de.chriscohnen.islandr.webhook.WebhookEventType;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Auto-revokes ad-hoc temporary direct grants (issue #70) once their
 * {@code validUntil} passes — the same "system-actor scheduled job flips
 * enforcement state, then audits + recomputes" shape as
 * {@link de.chriscohnen.islandr.peer.PeerScheduleJob}. The audit action is
 * deliberately {@code user_grant.expire}, distinct from the admin-triggered
 * {@code user_grant.delete} the ACL page's own revoke button produces — the
 * timeline should read honestly that nobody clicked revoke, time simply
 * ran out.
 */
@ApplicationScoped
public class UserGrantExpiryJob {

    private static final Logger LOG = Logger.getLogger(UserGrantExpiryJob.class);
    private static final String SYSTEM_ACTOR = "system:user-grant-expiry";

    @Inject UserGrantService grants;
    @Inject AuditService audit;
    @Inject RulesetService rulesets;
    @Inject WebhookDispatcher webhooks;

    @Scheduled(every = "60s",
               identity = "islandr-user-grant-expiry",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        try {
            expireDue();
        } catch (Exception ex) {
            // Same posture as ActivityPoller/PeerScheduleJob: never let a
            // tick failure crash the scheduler — the next tick gets another shot.
            LOG.warnf("user-grant expiry tick failed: %s", ex.getMessage());
        }
    }

    @Transactional
    void expireDue() {
        List<String> dueIds = grants.dueForExpiry(Instant.now());
        if (dueIds.isEmpty()) return;

        boolean anyExpired = false;
        for (String grantId : dueIds) {
            UserGrantService.ExpiredGrant snapshot = grants.expire(grantId);
            if (snapshot == null) continue; // raced with a manual revoke — nothing to report
            anyExpired = true;

            User user = User.findById(snapshot.userId());
            Resource res = Resource.findById(snapshot.resourceId());
            String userName = user == null ? snapshot.userId() : user.name;
            String resourceName = res == null ? snapshot.resourceId() : res.name;

            audit.logEvent(SYSTEM_ACTOR, "user_grant.expire", "UserGrant:" + userName + "/" + resourceName,
                    Map.of("reason", "validUntil elapsed"));
            webhooks.publish(WebhookEventType.ACL_GRANT_REVOKED, SYSTEM_ACTOR,
                    "Grant:" + userName + "/" + resourceName,
                    Map.of("user", userName, "resource", resourceName, "reason", "expired"));
        }
        if (anyExpired) rulesets.recomputeFromHook();
    }
}
