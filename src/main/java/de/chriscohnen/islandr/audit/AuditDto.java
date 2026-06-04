package de.chriscohnen.islandr.audit;

import java.time.Instant;

public final class AuditDto {

    public record Response(
            String id,
            String actor,
            String action,
            String target,
            String meta,        // raw JSON string; client renders as collapsed details
            Instant createdAt
    ) {
        public static Response from(AuditLog a) {
            return new Response(a.id, a.actor, a.action, a.target, a.metaJson, a.createdAt);
        }
    }

    private AuditDto() {}
}
