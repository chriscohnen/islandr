package de.chriscohnen.islandr.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class OidcProviderService {

    public List<OidcProvider> listAll() {
        return OidcProvider.<OidcProvider>listAll();
    }

    public OidcProvider get(String key) {
        OidcProvider p = OidcProvider.findById(key);
        if (p == null) throw new NotFoundException("unknown OIDC provider: " + key);
        return p;
    }

    /**
     * Result of an update — the updated provider plus the list of providers
     * that the mutual-exclusion rule turned off as a side effect. The Resource
     * uses the side-effect list to emit one audit row per affected provider,
     * so an admin can see "Microsoft was disabled because Google was enabled"
     * instead of an unexplained gap.
     */
    public record UpdateResult(OidcProvider provider, List<String> deactivatedOthers) {}

    @Transactional
    public UpdateResult update(String key, OidcProviderDto.UpdateRequest req, String actor) {
        OidcProvider p = get(key);

        if (req.clientId() != null) p.clientId = blankToNull(req.clientId());
        if (req.tenantId() != null) p.tenantId = blankToNull(req.tenantId());
        if (req.allowedDomains() != null) p.allowedDomains = normaliseDomains(req.allowedDomains());

        // Treat empty/blank clientSecret as "no change". Real rotation = send a value.
        if (req.clientSecret() != null && !req.clientSecret().isBlank()) {
            p.clientSecret = req.clientSecret();
        }

        List<String> deactivated = List.of();
        if (req.enabled() != null) {
            if (req.enabled()) {
                requireEnableable(p);
                // Mutual exclusion: at most one OIDC provider may be active at a time.
                // When enabling this one, deactivate every other already-enabled provider.
                // Frontend confirms the swap with the user before sending the request.
                deactivated = deactivateOthers(p.providerKey, actor);
            }
            p.enabled = req.enabled();
        }

        p.updatedAt = Instant.now();
        p.updatedBy = actor;
        return new UpdateResult(p, deactivated);
    }

    private List<String> deactivateOthers(String keepKey, String actor) {
        List<String> deactivated = new java.util.ArrayList<>();
        for (OidcProvider other : OidcProvider.<OidcProvider>listAll()) {
            if (!other.providerKey.equals(keepKey) && other.enabled) {
                other.enabled = false;
                other.updatedAt = Instant.now();
                other.updatedBy = actor;
                deactivated.add(other.providerKey);
            }
        }
        return deactivated;
    }

    private static void requireEnableable(OidcProvider p) {
        if (p.clientId == null || p.clientId.isBlank()) {
            throw new BadRequestException("clientId is required to enable " + p.providerKey);
        }
        if (p.clientSecret == null || p.clientSecret.isBlank()) {
            throw new BadRequestException("clientSecret is required to enable " + p.providerKey);
        }
        if (p.isMicrosoft() && (p.tenantId == null || p.tenantId.isBlank())) {
            throw new BadRequestException("tenantId is required to enable microsoft (single-tenant)");
        }
    }

    /**
     * Lowercased, trimmed, deduplicated CSV. Empty string → null.
     */
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
