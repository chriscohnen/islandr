package de.chriscohnen.islandr.audit;

import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /**
     * Reads the log, newest first. Cursor pagination via {@code before}: the
     * first page omits it, every following page passes the {@code createdAt} of
     * the oldest entry it received. {@code actor} and {@code action} match
     * exactly — no substring search, since a prefix match on an action name
     * would quietly widen what an access review returns.
     *
     * <p>Lives here rather than in the resource because two callers read the
     * log now — the admin console and the external facade — and a second copy
     * of a filter is a second place for it to drift.
     */
    public List<AuditLog> query(Instant before, String actor, String action, Integer limit) {
        int n = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<String> where = new ArrayList<>();
        Parameters params = new Parameters();
        if (before != null) {
            where.add("createdAt < :before");
            params.and("before", before);
        }
        if (actor != null && !actor.isBlank()) {
            where.add("actor = :actor");
            params.and("actor", actor);
        }
        if (action != null && !action.isBlank()) {
            where.add("action = :action");
            params.and("action", action);
        }
        Sort newestFirst = Sort.by("createdAt").descending().and("id");
        var q = where.isEmpty()
                ? AuditLog.<AuditLog>findAll(newestFirst)
                : AuditLog.<AuditLog>find(String.join(" and ", where), newestFirst, params);
        return q.page(0, n).list();
    }
}
