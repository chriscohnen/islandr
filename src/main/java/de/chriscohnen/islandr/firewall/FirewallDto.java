package de.chriscohnen.islandr.firewall;

import java.time.Instant;

public final class FirewallDto {

    public record Response(
            String status,             // 'ok' | 'failed' | 'never'
            int ruleCount,
            Instant lastAttemptAt,
            Instant lastOkAt,
            String rulesetText,        // current authoritative ruleset, may be null
            String stderr,             // present on status='failed'
            boolean dryRun             // true = writes paused via Settings toggle
    ) {
        public static Response from(FirewallState s, boolean dryRun) {
            return new Response(s.lastStatus, s.ruleCount,
                    s.lastAttemptAt, s.lastOkAt, s.rulesetText, s.stderrText, dryRun);
        }
    }

    private FirewallDto() {}
}
