package de.chriscohnen.islandr.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Single lookup point across the two hardcoded providers ({@link OidcProvider})
 * and any number of admin-configured generic providers ({@link OidcCustomProvider})
 * — issue #69. {@link OidcLoginService} and {@link IdTokenVerifier} go through
 * this exclusively so a third provider "kind" doesn't need its own login/verify
 * code path, only its own row-to-{@link ResolvedOidcProvider} mapping here.
 *
 * <p>Also owns the cross-table mutual-exclusion rule ("at most one OIDC
 * provider active at a time" — unchanged from the MS365/Google-only era,
 * just widened to cover every custom provider too): enabling any one
 * disables every other, regardless of which table it lives in.
 */
@ApplicationScoped
public class OidcProviderRegistry {

    public Optional<ResolvedOidcProvider> find(String key) {
        OidcProvider fixed = OidcProvider.findById(key);
        if (fixed != null) {
            return Optional.of(resolve(fixed));
        }
        OidcCustomProvider custom = OidcCustomProvider.findById(key);
        if (custom != null) {
            return Optional.of(resolve(custom));
        }
        return Optional.empty();
    }

    public static ResolvedOidcProvider resolve(OidcProvider p) {
        return new ResolvedOidcProvider(
                p.providerKey, p.providerKey, // kind == key for the two hardcoded ones
                p.enabled, p.clientId, p.clientSecret, p.allowedDomains,
                ProviderEndpoints.scopesFor(p),
                ProviderEndpoints.forProvider(p),
                p.tenantId);
    }

    public static ResolvedOidcProvider resolve(OidcCustomProvider p) {
        ProviderEndpoints.Endpoints ep = new ProviderEndpoints.Endpoints(
                p.authorizeEndpoint, p.tokenEndpoint, p.jwksUri, p.discoveredIssuer, p.userinfoEndpoint);
        return new ResolvedOidcProvider(
                p.id, "custom", p.enabled, p.clientId, p.clientSecret, p.allowedDomains, p.scopes, ep, null);
    }

    /**
     * Disables every enabled provider except {@code keepKey}, across both
     * tables. Returns the keys that were actually turned off, so callers can
     * emit one audit row per affected sibling — same UX as today's
     * "Microsoft was disabled because Google was enabled" transparency.
     */
    @Transactional
    public List<String> deactivateAllExcept(String keepKey, String actor) {
        List<String> deactivated = new ArrayList<>();
        for (OidcProvider other : OidcProvider.<OidcProvider>listAll()) {
            if (!other.providerKey.equals(keepKey) && other.enabled) {
                other.enabled = false;
                other.updatedAt = Instant.now();
                other.updatedBy = actor;
                deactivated.add(other.providerKey);
            }
        }
        for (OidcCustomProvider other : OidcCustomProvider.<OidcCustomProvider>listAll()) {
            if (!other.id.equals(keepKey) && other.enabled) {
                other.enabled = false;
                other.updatedAt = Instant.now();
                other.updatedBy = actor;
                deactivated.add(other.id);
            }
        }
        return deactivated;
    }
}
