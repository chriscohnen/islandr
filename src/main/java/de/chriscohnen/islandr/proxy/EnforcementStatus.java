package de.chriscohnen.islandr.proxy;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Instant;

/**
 * In-memory single source of truth for whether the host enforcement plane
 * (the {@code wg}/{@code nft} operations behind the socket proxy) is currently
 * reachable and applied (design §3, D2).
 *
 * <p>Not a DB table: intent is already durable (peers, grants). With the proxy
 * down the container cannot read host kernel state, so <em>everything</em> is
 * pending by definition; "pending" simply means "not reconciled since the proxy
 * last became available". Reconcile is a full re-apply (BR-025), so no delta
 * needs persisting.
 *
 * <p>Default state is {@link State#ACTIVE}: in {@code real}/{@code mock} mode
 * enforcement is direct and the banner stays hidden. Socket mode flips this at
 * boot via the reconciler if the proxy is unreachable. Written by the degraded
 * call-sites and {@code ProxyReconciler}, read by the enforcement-status
 * endpoint; guarded for concurrent access.
 */
@ApplicationScoped
public class EnforcementStatus {

    public enum State { ACTIVE, UNAVAILABLE, RECONCILING }

    private final Clock clock;

    private volatile State state = State.ACTIVE;
    private volatile Instant lastReconcileAt;
    private volatile Instant lastProbeAt;
    private volatile String lastError;

    public EnforcementStatus() {
        this(Clock.systemUTC());
    }

    /** Test seam: a fixed clock makes the recorded timestamps assertable. */
    EnforcementStatus(Clock clock) {
        this.clock = clock;
    }

    public State state() {
        return state;
    }

    public Instant lastReconcileAt() {
        return lastReconcileAt;
    }

    public Instant lastProbeAt() {
        return lastProbeAt;
    }

    public String lastError() {
        return lastError;
    }

    /** Enforcement reachable and fully applied: clears the error, stamps the reconcile time. */
    public synchronized void markActive() {
        this.state = State.ACTIVE;
        this.lastError = null;
        Instant now = clock.instant();
        this.lastProbeAt = now;
        this.lastReconcileAt = now;
    }

    /** A full re-apply is in progress after the proxy reappeared. */
    public synchronized void markReconciling() {
        this.state = State.RECONCILING;
        this.lastProbeAt = clock.instant();
    }

    /** Proxy unreachable: config still persists, nothing is enforced, reason recorded. */
    public synchronized void markUnavailable(String error) {
        this.state = State.UNAVAILABLE;
        this.lastError = error;
        this.lastProbeAt = clock.instant();
    }
}
