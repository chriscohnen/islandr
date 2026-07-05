package de.chriscohnen.islandr.proxy;

import java.util.Optional;

/**
 * Shared mode-resolution rule for the WireGuard and nftables adapter producers
 * (design §8, D3), so both surfaces resolve identically.
 *
 * <p>Rule: an explicit config value always wins; an unset value defaults to
 * {@code socket} inside a container and {@code mock} on a bare host. "In a
 * container" is only a fallback <em>default</em>, never an override — so the
 * proxy path is never taken outside a container unless it was asked for by name.
 */
public final class AdapterMode {

    private AdapterMode() {
    }

    public static String resolve(Optional<String> explicit, boolean inContainer) {
        if (explicit.isPresent() && !explicit.get().isBlank()) {
            return explicit.get().trim().toLowerCase();
        }
        return inContainer ? "socket" : "mock";
    }
}
