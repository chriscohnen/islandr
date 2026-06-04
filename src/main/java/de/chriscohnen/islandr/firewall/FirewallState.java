package de.chriscohnen.islandr.firewall;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Singleton mirror of the last nftables apply. The kernel is the actual
 * source of truth for what's enforced; this row is the audit + UI surface.
 *
 * <p>{@code lastStatus} is one of:
 * <ul>
 *   <li>{@code never} — fresh DB, apply has not run yet</li>
 *   <li>{@code ok}    — last apply succeeded; {@code stderrText} is null</li>
 *   <li>{@code failed} — {@code nft -c -f} rejected the ruleset; kernel
 *       still runs the previous good one, {@code stderrText} carries the
 *       error so the UI can render it.</li>
 * </ul>
 */
@Entity
@Table(name = "firewall_state")
public class FirewallState extends PanacheEntityBase {

    public static final int SINGLETON_ID = 1;

    public static final String NEVER = "never";
    public static final String OK = "ok";
    public static final String FAILED = "failed";

    @Id
    @Column(name = "id", nullable = false)
    public int id;

    @Column(name = "last_status", nullable = false, length = 16)
    public String lastStatus;

    @Column(name = "last_attempt_at")
    public Instant lastAttemptAt;

    @Column(name = "last_ok_at")
    public Instant lastOkAt;

    @Column(name = "rule_count", nullable = false)
    public int ruleCount;

    @Column(name = "ruleset_text", columnDefinition = "TEXT")
    public String rulesetText;

    @Column(name = "stderr_text", columnDefinition = "TEXT")
    public String stderrText;

    public static FirewallState get() {
        FirewallState s = FirewallState.findById(SINGLETON_ID);
        if (s == null) {
            // V11 seeds the row; this branch only fires if someone deleted it.
            s = new FirewallState();
            s.id = SINGLETON_ID;
            s.lastStatus = NEVER;
            s.ruleCount = 0;
            s.persist();
        }
        return s;
    }
}
