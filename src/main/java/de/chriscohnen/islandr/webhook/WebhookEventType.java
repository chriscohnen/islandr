package de.chriscohnen.islandr.webhook;

import java.util.List;

/**
 * Canonical event-type keys a webhook can subscribe to (issue #68). Mirrors
 * the candidate list from the issue — one dotted key per event, stable
 * across releases (used as the literal value stored in a webhook's
 * {@code event_types} CSV, so renaming one silently un-subscribes every
 * webhook that had it — treat these as append-only).
 */
public final class WebhookEventType {

    public static final String PEER_CONNECTED = "peer.connected";
    public static final String PEER_DISCONNECTED = "peer.disconnected";
    public static final String PEER_ENABLED = "peer.enabled";
    public static final String PEER_DISABLED = "peer.disabled";
    public static final String ACL_GRANT_CREATED = "acl.grant_created";
    public static final String ACL_GRANT_REVOKED = "acl.grant_revoked";
    public static final String DISCOVERY_SCAN_COMPLETED = "discovery.scan_completed";
    public static final String ACME_CERT_RENEWED = "acme.cert_renewed";
    public static final String ACME_CERT_RENEWAL_FAILED = "acme.cert_renewal_failed";

    /** monitoring-status-endpoint (#71): nftables rejected or failed to apply the
     *  ruleset. Fired on the transition into the failed state, not on every retry. */
    public static final String FIREWALL_APPLY_FAILED = "firewall.apply_failed";

    /** backup-schedule: a scheduled backup did not complete. Not fired for a
     *  manual run — the admin who clicked it sees the error right there. */
    public static final String BACKUP_FAILED = "backup.failed";

    /** webhook-user-events: user lifecycle, for provisioning into other systems.
     *  A plain login never fires {@code user.updated}. */
    public static final String USER_CREATED = "user.created";
    public static final String USER_UPDATED = "user.updated";
    public static final String USER_ENABLED = "user.enabled";
    public static final String USER_DISABLED = "user.disabled";
    public static final String USER_DELETED = "user.deleted";

    /** Used only by the admin UI's "test connection" action — never a real
     *  subscribable filter value, delivered regardless of a webhook's
     *  event_types selection. */
    public static final String TEST = "webhook.test";

    /** Every real, subscribable event type — drives the admin UI's filter
     *  checkboxes and validates incoming subscription requests. */
    public static final List<String> ALL = List.of(
            PEER_CONNECTED, PEER_DISCONNECTED, PEER_ENABLED, PEER_DISABLED,
            ACL_GRANT_CREATED, ACL_GRANT_REVOKED,
            DISCOVERY_SCAN_COMPLETED,
            ACME_CERT_RENEWED, ACME_CERT_RENEWAL_FAILED,
            FIREWALL_APPLY_FAILED,
            BACKUP_FAILED,
            USER_CREATED, USER_UPDATED, USER_ENABLED, USER_DISABLED, USER_DELETED
    );

    private WebhookEventType() {}
}
