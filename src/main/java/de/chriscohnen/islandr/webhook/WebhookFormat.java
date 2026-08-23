package de.chriscohnen.islandr.webhook;

import java.util.List;

/**
 * Which payload shape a webhook expects (issue #68 follow-up). Most
 * third-party notification services don't understand Islandr's own
 * HMAC-signed envelope — they have their own push API shape. Each format
 * beyond {@link #GENERIC} is a small, self-contained render function in
 * {@link WebhookDispatcher}; adding a new one (Discord, Slack, Matrix,
 * Telegram, ...) means one more constant here and one more branch there,
 * nothing else changes.
 */
public final class WebhookFormat {

    /** Islandr's own envelope: {@code {event, timestamp, actor, target, data}},
     *  HMAC-SHA256-signed with the webhook's secret. */
    public static final String GENERIC = "generic";

    /** Gotify's push API shape: {@code {title, message, priority}}, POSTed to
     *  the admin-supplied URL (which already includes {@code ?token=...} or
     *  is fronted by a reverse proxy that injects it) — Gotify has no HMAC
     *  verification story, so no signature header is sent for this format. */
    public static final String GOTIFY = "gotify";

    public static final List<String> ALL = List.of(GENERIC, GOTIFY);

    private WebhookFormat() {}
}
