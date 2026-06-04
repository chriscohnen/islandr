package de.chriscohnen.islandr.firewall;

import java.time.Instant;

public final class FirewallDto {

    public record Response(
            String status,             // 'ok' | 'failed' | 'never'
            int ruleCount,
            Instant lastAttemptAt,
            Instant lastOkAt,
            String rulesetText,        // current authoritative ruleset, may be null
            String stderr              // present on status='failed'
    ) {
        public static Response from(FirewallState s) {
            return new Response(s.lastStatus, s.ruleCount,
                    s.lastAttemptAt, s.lastOkAt, s.rulesetText, s.stderrText);
        }
    }

    private FirewallDto() {}
}
