package de.chriscohnen.islandr.proxy;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parsed response from the host-side proxy for one request.
 *
 * <p>Protocol (design §4): {@code {"ok":true,...}} or
 * {@code {"ok":false,"error":"..."}}. {@link #ok()} and {@link #error()} are the
 * decoded convenience fields; {@link #body()} is the full response node so the
 * adapters can read op-specific fields (e.g. the {@code wg_show} dump).
 *
 * <p>A response with {@code ok=false} is an <em>operational</em> failure reported
 * by a reachable proxy — the caller maps it to a {@code WgException} /
 * {@code NftablesException}. Unreachability is signalled separately via
 * {@link ProxyUnavailableException}.
 */
public record ProxyResponse(boolean ok, String error, JsonNode body) {}
