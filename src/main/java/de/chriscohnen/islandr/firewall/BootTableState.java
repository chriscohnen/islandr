package de.chriscohnen.islandr.firewall;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

/**
 * Where the fail-closed boot table stands (ADR-0031, issue #84).
 *
 * <p>Two of these states have to be visible, not just logged, because both
 * look like a network fault from the outside:
 *
 * <ul>
 *   <li><b>Still active</b> — firewall writes are paused, so the boot table is
 *       deliberately kept and WireGuard forwarding is dropped. Handshakes
 *       succeed and nothing routes, which reads as a routing problem unless it
 *       is named (<b>R-193</b>).</li>
 *   <li><b>Handover failed</b> — Islandr's table is live but the boot table is
 *       still there too. Both {@code forward} hooks run and a drop in either
 *       wins, so every granted flow stays blocked (<b>R-194</b>).</li>
 * </ul>
 *
 * <p>In memory, like {@link de.chriscohnen.islandr.proxy.EnforcementStatus}:
 * it describes the kernel right now, and the kernel is re-read at every boot.
 */
@ApplicationScoped
public class BootTableState {

    public enum Handover {
        /** No handover attempted yet this run. */
        UNKNOWN,
        /** The boot table is gone; Islandr's ruleset is the only one. */
        DONE,
        /** No boot table was installed — an upgrade without the unit. */
        NOT_INSTALLED,
        /** Kept on purpose: firewall writes are paused. WireGuard forwarding is dropped. */
        ACTIVE_DRY_RUN,
        /** Both tables are live. Granted traffic stays blocked until this is fixed. */
        FAILED
    }

    private volatile Handover handover = Handover.UNKNOWN;
    private volatile Instant lastAttemptAt;

    public Handover handover() {
        return handover;
    }

    public Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    /** True while the boot table is known to still be filtering. */
    public boolean stillFiltering() {
        return handover == Handover.ACTIVE_DRY_RUN || handover == Handover.FAILED;
    }

    /** True when another attempt would be worth making on the next successful apply. */
    public boolean needsHandover() {
        return handover == Handover.UNKNOWN
                || handover == Handover.FAILED
                || handover == Handover.ACTIVE_DRY_RUN;
    }

    public void record(NftablesAdapter.BootTableHandover result) {
        this.handover = switch (result) {
            case REMOVED -> Handover.DONE;
            case ABSENT, UNSUPPORTED -> Handover.NOT_INSTALLED;
            case KEPT_DRY_RUN -> Handover.ACTIVE_DRY_RUN;
            case FAILED -> Handover.FAILED;
        };
        this.lastAttemptAt = Instant.now();
    }
}
