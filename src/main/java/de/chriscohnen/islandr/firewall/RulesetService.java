package de.chriscohnen.islandr.firewall;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.proxy.EnforcementStatus;
import de.chriscohnen.islandr.proxy.ProxyUnavailableException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orchestrates recompute → validate → apply and keeps {@link FirewallState}
 * in sync. The flow is the one PRD F-08 and ADR-0003 describe.
 *
 * <p>{@link #recomputeAndApply} is the single entry point every domain
 * service calls after a mutation. It NEVER throws — apply failures go
 * into {@link FirewallState#lastStatus}/{@code stderrText} and surface in
 * the UI. The mutation's own transaction still commits so the DB stays
 * the source of truth, and the operator can retry via {@code /firewall/resync}.
 */
@ApplicationScoped
public class RulesetService {

    private static final Logger LOG = Logger.getLogger(RulesetService.class);

    @Inject RuleBuilder builder;
    @Inject NftablesAdapter adapter;
    @Inject AuditService audit;
    @Inject EnforcementStatus enforcement;

    /**
     * Build a fresh ruleset from DB state and apply it. Persists the result
     * to {@link FirewallState} and writes an audit row. Safe to call
     * concurrently — Hibernate locks the singleton row inside its own TX.
     *
     * @param actor whoever triggered the recompute (free-form principal).
     *              "system:boot" for startup, "system:hook" for cascading
     *              recomputes from another mutation, an email for an
     *              explicit /resync.
     * @return the resulting state record (so the caller can render it back
     *         to the UI immediately).
     */
    @Transactional
    public FirewallState recomputeAndApply(String actor) {
        RuleBuilder.Snapshot snap = builder.build();
        FirewallState state = FirewallState.get();
        state.lastAttemptAt = Instant.now();

        NftablesAdapter.ValidationResult validation;
        try {
            validation = adapter.validate(snap.rulesetText());
        } catch (ProxyUnavailableException ex) {
            // Enforcement plane unreachable (socket proxy down): the config still
            // committed, we just cannot push it. Record it honestly and leave
            // FirewallState untouched — FAILED is reserved for a real nft rejection.
            LOG.warnf("enforcement unavailable during validate — config persisted, not enforced: %s", ex.getMessage());
            enforcement.markUnavailable(ex.getMessage());
            return state;
        }
        if (!validation.ok()) {
            LOG.warnf("nftables validation failed: %s", validation.stderr());
            state.lastStatus = FirewallState.FAILED;
            state.stderrText = validation.stderr();
            // Keep the previous ruleset_text + rule_count so the UI can show
            // "last good was N rules at T". Only the freshly attempted text
            // would be misleading here.
            audit.logEvent(actor, "firewall.apply_failed", "Firewall:ruleset",
                    auditDetails(snap, validation.stderr()));
            return state;
        }

        try {
            adapter.apply(snap.rulesetText());
        } catch (ProxyUnavailableException ex) {
            LOG.warnf("enforcement unavailable during apply — config persisted, not enforced: %s", ex.getMessage());
            enforcement.markUnavailable(ex.getMessage());
            return state;
        } catch (NftablesException ex) {
            LOG.errorf(ex, "nftables apply failed despite validation passing");
            state.lastStatus = FirewallState.FAILED;
            state.stderrText = ex.getMessage();
            audit.logEvent(actor, "firewall.apply_failed", "Firewall:ruleset",
                    auditDetails(snap, ex.getMessage()));
            return state;
        }

        // Enforcement plane reachable and applied — clear any prior degraded state.
        enforcement.markActive();
        state.lastStatus = FirewallState.OK;
        state.lastOkAt = state.lastAttemptAt;
        state.ruleCount = snap.ruleCount();
        state.rulesetText = snap.rulesetText();
        state.stderrText = null;
        // Audit the successful apply too — operators need to know "what
        // changed and when". Don't dump the full ruleset into the audit
        // JSON (could be MB at scale); just the rule count.
        audit.logEvent(actor, "firewall.apply_ok", "Firewall:ruleset",
                Map.of("ruleCount", snap.ruleCount(), "rulesetBytes", snap.rulesetText().length()));
        return state;
    }

    /** Hook for the domain services: a no-name change just calls this. */
    public FirewallState recomputeFromHook() {
        return recomputeAndApply("system:hook");
    }

    private static Map<String, Object> auditDetails(RuleBuilder.Snapshot snap, String stderr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleCount", snap.ruleCount());
        m.put("rulesetBytes", snap.rulesetText().length());
        m.put("stderr", stderr);
        return m;
    }
}
