package de.chriscohnen.islandr.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Map;

/**
 * Writes audit entries (PRD F-10). Three convenience overloads cover the
 * common shapes — pure create, pure delete, and update with before/after.
 *
 * <p>Calls run in the same transaction as the action that triggered them —
 * the {@code @Transactional} REQUIRED default participates, so either both
 * the domain mutation and the audit row commit, or neither does. That's the
 * point: audit-log entries that don't reflect a real change would be worse
 * than no log at all.
 */
@ApplicationScoped
public class AuditService {

    /**
     * Log a creation. {@code after} carries the persisted state — the helper
     * inverts the diff so the JSON ends up shaped {@code { after: {...} }}.
     */
    @Transactional
    public void logCreate(String actor, String action, String target, Map<String, Object> after) {
        AuditLog.of(actor, action, target, AuditDiff.build(null, after)).persist();
    }

    /**
     * Log a deletion. {@code before} is the last-known state — diff ends up
     * shaped {@code { before: {...} }}.
     */
    @Transactional
    public void logDelete(String actor, String action, String target, Map<String, Object> before) {
        AuditLog.of(actor, action, target, AuditDiff.build(before, null)).persist();
    }

    /**
     * Log an update. Only fields that actually changed land in the JSON;
     * if {@code before} equals {@code after}, no row is written at all
     * (an idempotent-update PUT should not pollute the audit).
     */
    @Transactional
    public void logUpdate(String actor, String action, String target,
                          Map<String, Object> before, Map<String, Object> after) {
        String json = AuditDiff.build(before, after);
        if (json == null) return;  // no-op update
        AuditLog.of(actor, action, target, json).persist();
    }

    /**
     * Log an action that doesn't fit the before/after model — e.g. a login
     * attempt or a 'feature triggered' event. {@code details} is opaque map
     * data that goes into the JSON under a {@code details} key (still
     * redacted for sensitive keys).
     */
    @Transactional
    public void logEvent(String actor, String action, String target, Map<String, Object> details) {
        AuditLog.of(actor, action, target, AuditDiff.details(details)).persist();
    }
}
