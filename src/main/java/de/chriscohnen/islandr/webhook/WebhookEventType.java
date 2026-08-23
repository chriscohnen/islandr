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
            ACME_CERT_RENEWED, ACME_CERT_RENEWAL_FAILED
    );

    private WebhookEventType() {}
}
