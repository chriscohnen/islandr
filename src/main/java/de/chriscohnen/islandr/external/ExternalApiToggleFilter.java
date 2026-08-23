package de.chriscohnen.islandr.external;

import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Opt-out gate for the external automation API facade (issue #15,
 * ADR-0026, {@code Settings.externalApiEnabled}). When disabled, every
 * {@code /api/external/v1/*} route 404s regardless of a valid API key —
 * a further, explicit hardening switch for operators who never intend to
 * use the facade, on top of (not instead of) API-key auth.
 *
 * <p>404, not 401/403: same "don't even confirm this exists" posture the
 * DNS resolver (ADR-0023) already applies to unauthorized lookups — an
 * operator who has turned the facade off gets an indistinguishable
 * "not found" whether they hold a valid key or not.
 */
@Provider
public class ExternalApiToggleFilter implements ContainerRequestFilter {

    private static final String PATH_PREFIX = "api/external/v1";

    @Inject SettingsService settings;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path == null) return;
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (!normalized.startsWith(PATH_PREFIX)) return;
        if (!settings.get().externalApiEnabled) {
            throw new NotFoundException();
        }
    }
}
