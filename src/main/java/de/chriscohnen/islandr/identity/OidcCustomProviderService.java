package de.chriscohnen.islandr.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * CRUD + discovery + mutual-exclusion for admin-configured generic OIDC
 * providers (issue #69) — the counterpart of {@link OidcProviderService} for
 * everything that isn't the two hardcoded MS365/Google rows.
 */
@ApplicationScoped
public class OidcCustomProviderService {

    @Inject OidcDiscoveryClient discovery;
    @Inject OidcProviderRegistry registry;

    public List<OidcCustomProvider> listAll() {
        return OidcCustomProvider.<OidcCustomProvider>listAll();
    }

    public OidcCustomProvider get(String id) {
        OidcCustomProvider p = OidcCustomProvider.findById(id);
        if (p == null) throw new NotFoundException("unknown custom OIDC provider: " + id);
        return p;
    }

    @Transactional
    public OidcCustomProvider create(OidcCustomProviderDto.CreateRequest req, String actor) {
        String issuerUrl = resolveIssuerUrl(req.preset(), req.domain(), req.issuerUrl());

        OidcCustomProvider p = new OidcCustomProvider();
        p.id = UUID.randomUUID().toString();
        p.preset = req.preset();
        p.displayName = req.displayName();
        p.issuerUrl = issuerUrl;
        p.clientId = blankToNull(req.clientId());
        p.clientSecret = blankToNull(req.clientSecret());
        p.allowedDomains = normaliseDomains(req.allowedDomains());
        p.enabled = false;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.updatedBy = actor;

        applyDiscovery(p, issuerUrl);
        p.persist();
        return p;
    }

    /** Result of an update — mirrors {@link OidcProviderService.UpdateResult}. */
    public record UpdateResult(OidcCustomProvider provider, List<String> deactivatedOthers) {}

    @Transactional
    public UpdateResult update(String id, OidcCustomProviderDto.UpdateRequest req, String actor) {
        OidcCustomProvider p = get(id);

        if (req.displayName() != null && !req.displayName().isBlank()) p.displayName = req.displayName().trim();
        if (req.clientId() != null) p.clientId = blankToNull(req.clientId());
        if (req.allowedDomains() != null) p.allowedDomains = normaliseDomains(req.allowedDomains());
        if (req.clientSecret() != null && !req.clientSecret().isBlank()) p.clientSecret = req.clientSecret();

        if (req.issuerUrl() != null && !req.issuerUrl().isBlank() && !req.issuerUrl().trim().equals(p.issuerUrl)) {
            p.issuerUrl = req.issuerUrl().trim();
            applyDiscovery(p, p.issuerUrl);
        }

        List<String> deactivated = List.of();
        if (req.enabled() != null) {
            if (req.enabled()) {
                requireEnableable(p);
                deactivated = registry.deactivateAllExcept(p.id, actor);
            }
            p.enabled = req.enabled();
        }

        p.updatedAt = Instant.now();
        p.updatedBy = actor;
        return new UpdateResult(p, deactivated);
    }

    /** Re-runs discovery without touching any other field — the admin UI's
     *  explicit "rediscover"/"test connection" action. */
    @Transactional
    public OidcCustomProvider rediscover(String id, String actor) {
        OidcCustomProvider p = get(id);
        applyDiscovery(p, p.issuerUrl);
        p.updatedAt = Instant.now();
        p.updatedBy = actor;
        return p;
    }

    @Transactional
    public void delete(String id) {
        OidcCustomProvider p = get(id);
        if (p.enabled) {
            throw new BadRequestException("disable " + p.displayName + " before deleting it");
        }
        p.delete();
    }

    // -- helpers --------------------------------------------------------

    private void applyDiscovery(OidcCustomProvider p, String issuerUrl) {
        OidcDiscoveryClient.Discovered d = discovery.discover(issuerUrl);
        p.authorizeEndpoint = d.authorizationEndpoint();
        p.tokenEndpoint = d.tokenEndpoint();
        p.jwksUri = d.jwksUri();
        p.userinfoEndpoint = d.userinfoEndpoint();
        p.discoveredIssuer = d.issuer();
        p.discoveredAt = Instant.now();
    }

    private static String resolveIssuerUrl(String preset, String domain, String issuerUrl) {
        if (preset == null || preset.isBlank()) {
            if (issuerUrl == null || issuerUrl.isBlank()) {
                throw new BadRequestException("issuerUrl is required when no preset is selected");
            }
            return issuerUrl.trim();
        }
        if (domain == null || domain.isBlank()) {
            throw new BadRequestException("domain is required for the " + preset + " preset");
        }
        // Admin may paste a full https://... URL out of habit — tolerate it by
        // stripping any scheme/trailing path, we only want the bare host.
        String host = domain.trim()
                .replaceFirst("^https?://", "")
                .replaceFirst("/.*$", "");
        return switch (preset) {
            case OidcCustomProvider.PRESET_AUTH0 -> "https://" + host + "/";
            // Okta's default authorization server — an admin with a custom
            // authorization server needs the fully generic "issuerUrl" path
            // instead, not this preset.
            case OidcCustomProvider.PRESET_OKTA -> "https://" + host + "/oauth2/default";
            default -> throw new BadRequestException("unknown preset: " + preset);
        };
    }

    private static void requireEnableable(OidcCustomProvider p) {
        if (!p.isDiscovered()) {
            throw new BadRequestException("discovery has not succeeded yet for " + p.displayName);
        }
        if (p.clientId == null || p.clientId.isBlank()) {
            throw new BadRequestException("clientId is required to enable " + p.displayName);
        }
        if (p.clientSecret == null || p.clientSecret.isBlank()) {
            throw new BadRequestException("clientSecret is required to enable " + p.displayName);
        }
    }

    /** Same normalisation as {@link OidcProviderService#normaliseDomains}. */
    static String normaliseDomains(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(",");
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String s : parts) {
            String trimmed = s.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set.isEmpty() ? null : String.join(",", set);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
