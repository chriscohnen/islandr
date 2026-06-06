package de.chriscohnen.islandr.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionService;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * End-to-end OIDC login orchestration. Avoids quarkus-oidc deliberately so
 * provider config can live in the DB (GUI-editable) — see ADR / PRD §Identity.
 *
 * <p>State CSRF protection: the {@code state} param is a random 32-byte token
 * also stored in an HttpOnly cookie. On callback, the two must match.
 */
@ApplicationScoped
public class OidcLoginService {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject OidcProviderService providers;
    @Inject IdTokenVerifier idTokens;
    @Inject AvatarFetcher avatars;
    @Inject SessionService sessions;
    @Inject HttpFetcher http;
    @Inject SettingsService settings;
    @Inject AuditService audit;

    // Lazily initialised — see SessionService for the same native-image reason.
    private volatile SecureRandom rng;

    public record AuthorizeRedirect(String url, String state) {}

    public AuthorizeRedirect buildAuthorizeUrl(String providerKey, String redirectUri) {
        OidcProvider p = enabledOrThrow(providerKey);
        ProviderEndpoints.Endpoints ep = ProviderEndpoints.forProvider(p);
        String state = randomToken();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", p.clientId);
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("response_mode", "query");
        params.put("scope", ProviderEndpoints.scopesFor(p));
        params.put("state", state);
        return new AuthorizeRedirect(ep.authorize() + "?" + encodeQuery(params), state);
    }

    /**
     * Full callback handling. The session record returned is already persisted.
     * Throws {@link BadRequestException} when state mismatches, code missing,
     * or the email domain is not on the provider's allowlist.
     */
    @Transactional
    public Session handleCallback(String providerKey, String code, String state,
                                  String cookieState, String redirectUri) {
        if (state == null || cookieState == null || !state.equals(cookieState)) {
            throw new BadRequestException("state mismatch (CSRF protection)");
        }
        if (code == null || code.isBlank()) throw new BadRequestException("missing authorization code");

        OidcProvider p = enabledOrThrow(providerKey);
        ProviderEndpoints.Endpoints ep = ProviderEndpoints.forProvider(p);

        TokenResponse tokens = exchangeCode(p, ep, code, redirectUri);
        IdTokenVerifier.Claims claims = idTokens.verify(tokens.idToken(), p);
        if (claims.email() == null || claims.email().isBlank()) {
            throw new BadRequestException("ID-Token has no email claim — cannot identify user");
        }
        if (!domainAllowed(p, claims.email())) {
            throw new BadRequestException("email domain not in allowlist for " + p.providerKey);
        }

        // Treat null as true — null can occur on rows created before V17 migration
        boolean autoProvision = settings.get().oidcAutoProvision;
        UpsertResult upsert = upsertUser(p, claims, autoProvision);
        User u = upsert.user();
        cacheAvatar(p, u, claims, tokens.accessToken());
        Session s = sessions.create(p.providerKey, u.name, u.id);

        // Auto-provisioning a fresh org user is a privileged event — the
        // domain allowlist (or the IdP consent setup) just opened a new
        // account into Islandr. Log it before the login row so the timeline
        // reads "user provisioned, then logged in".
        if (upsert.provisioned()) {
            audit.logCreate(claims.email(), "user.provision_oidc", "User:" + u.id,
                    java.util.Map.of(
                            "name", u.name,
                            "email", u.email,
                            "oidcProvider", p.providerKey,
                            "oidcSubject", u.oidcSubject));
        }
        audit.logEvent(claims.email(), "auth.login_oidc", "Session:" + claims.email() + " (" + s.id + ")",
                java.util.Map.of("provider", p.providerKey, "userId", u.id));
        return s;
    }

    record UpsertResult(User user, boolean provisioned) {}

    // -- helpers ------------------------------------------------------------

    private OidcProvider enabledOrThrow(String key) {
        OidcProvider p;
        try {
            p = providers.get(key);
        } catch (NotFoundException nfe) {
            throw new BadRequestException("unknown provider: " + key);
        }
        if (!p.enabled) throw new BadRequestException("provider not enabled: " + key);
        if (p.clientId == null || p.clientSecret == null) {
            throw new BadRequestException("provider missing credentials: " + key);
        }
        return p;
    }

    record TokenResponse(String idToken, String accessToken) {}

    private TokenResponse exchangeCode(OidcProvider p, ProviderEndpoints.Endpoints ep,
                                       String code, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", p.clientId);
        form.put("client_secret", p.clientSecret);
        try {
            HttpFetcher.Response r = http.postForm(ep.token(), form, null);
            if (r.status() != 200) {
                throw new IllegalStateException("token exchange failed: HTTP " + r.status() + " — " + r.text());
            }
            JsonNode body = JSON.readTree(r.body());
            String idToken = body.path("id_token").asText(null);
            String accessToken = body.path("access_token").asText(null);
            if (idToken == null) throw new IllegalStateException("token response missing id_token");
            return new TokenResponse(idToken, accessToken);
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("token endpoint unreachable: " + ex.getMessage(), ex);
        }
    }

    private UpsertResult upsertUser(OidcProvider p, IdTokenVerifier.Claims claims, boolean autoProvision) {
        // First try (provider, subject) — stable across email changes in the IdP.
        User u = User.findByOidc(p.providerKey, claims.subject());
        boolean provisioned = false;
        if (u == null) {
            // Fallback: an admin-created local user with this email gets linked on first OIDC login.
            u = User.find("email", claims.email()).firstResult();
            if (u != null) {
                u.oidcProvider = p.providerKey;
                u.oidcSubject = claims.subject();
            }
        }
        if (u == null && !autoProvision) {
            throw new BadRequestException("no account found for " + claims.email()
                    + " — sign-up is disabled; an admin must create the account first");
        }
        if (u == null) {
            // Auto-provision new user. Domain allowlist was already enforced by caller.
            u = User.createNew(displayName(claims), claims.email());
            u.oidcProvider = p.providerKey;
            u.oidcSubject = claims.subject();
            u.preferredLocale = claims.locale();
            u.persist();
            provisioned = true;
        } else {
            // Keep display name fresh if the IdP one differs.
            String fresh = displayName(claims);
            if (fresh != null && !fresh.equals(u.name)) u.name = fresh;
            if (!claims.email().equals(u.email)) u.email = claims.email();
            // Only seed locale from IdP if the user has no stored preference yet.
            if (u.preferredLocale == null && claims.locale() != null) {
                u.preferredLocale = claims.locale();
            }
        }
        return new UpsertResult(u, provisioned);
    }

    private void cacheAvatar(OidcProvider p, User u, IdTokenVerifier.Claims claims, String accessToken) {
        // Precedence: if the Gravatar toggle is on AND the user has a Gravatar
        // for this email, that wins over the IdP picture. Reason: users
        // actively curate Gravatar (it's "my public face"), while OIDC photos
        // are often a forgotten Workspace upload. If no Gravatar, fall back
        // to the OIDC-provided photo (MS Graph or Google).
        AvatarFetcher.Avatar a = null;
        String source = null;
        if (settings.get().gravatarEnabled) {
            a = avatars.fetchGravatar(claims.email());
            if (a != null) source = "gravatar";
        }
        if (a == null) {
            if (p.isMicrosoft()) {
                a = avatars.fetchMicrosoft(accessToken);
            } else {
                // Google: try the ID-Token's picture claim first; if missing,
                // fall back to the userinfo endpoint (some Workspace setups
                // expose 'picture' only via userinfo, not directly in the token).
                a = avatars.fetchByUrl(claims.pictureUrl());
                if (a == null) {
                    ProviderEndpoints.Endpoints ep = ProviderEndpoints.forProvider(p);
                    String pic = avatars.fetchUserinfoPictureUrl(ep.userInfo(), accessToken);
                    if (pic != null) a = avatars.fetchByUrl(pic);
                }
            }
            if (a != null) source = "oidc";
        }
        if (a == null) {
            // Neither source returned bytes. If we previously had a Gravatar
            // cached and Gravatar just went away (deleted), clear the cache
            // so the frontend falls back to initials rather than showing a
            // stale photo. Don't touch an OIDC-sourced cache — that one
            // wasn't supposed to be refreshed by this branch anyway.
            if ("gravatar".equals(u.avatarSource) && settings.get().gravatarEnabled) {
                u.avatarBytes = null;
                u.avatarContentType = null;
                u.avatarEtag = null;
                u.avatarFetchedAt = null;
                u.avatarSource = null;
            }
            return;
        }
        // Unchanged content from the same source — skip the write. (source
        // is non-null in this branch because we only ever set `a` together
        // with `source` above, but make it explicit so static analysis agrees.)
        String src = source == null ? "oidc" : source;
        if (u.avatarEtag != null && u.avatarEtag.equals(a.etag()) && src.equals(u.avatarSource)) return;
        u.avatarBytes = a.bytes();
        u.avatarContentType = a.contentType();
        u.avatarEtag = a.etag();
        u.avatarFetchedAt = Instant.now();
        u.avatarSource = src;
    }

    private static String displayName(IdTokenVerifier.Claims c) {
        if (c.name() != null && !c.name().isBlank()) return c.name();
        return c.email();
    }

    private boolean domainAllowed(OidcProvider p, String email) {
        // Empty allowlist = "trust whatever the OAuth consent screen lets through".
        // The real first-line filter is the Google/MS consent setup itself:
        // Workspace 'Internal' or explicit test-users for Gmail-family deployments
        // already block unwanted accounts before the callback ever fires. The
        // allowlist is a second, email-domain-based layer for Workspaces that
        // span multiple domains and want to further restrict.
        if (p.allowedDomains == null || p.allowedDomains.isBlank()) return true;
        int at = email.indexOf('@');
        if (at < 0) return false;
        String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
        return Arrays.asList(p.allowedDomains.toLowerCase(Locale.ROOT).split(",")).contains(domain);
    }

    private String randomToken() {
        byte[] buf = new byte[32];
        rng().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private SecureRandom rng() {
        SecureRandom r = rng;
        if (r == null) {
            synchronized (this) {
                r = rng;
                if (r == null) {
                    r = new SecureRandom();
                    rng = r;
                }
            }
        }
        return r;
    }

    private static String encodeQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
