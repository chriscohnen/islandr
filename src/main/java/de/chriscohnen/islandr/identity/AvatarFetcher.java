package de.chriscohnen.islandr.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.user.AvatarImage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Best-effort avatar fetcher. Every call is wrapped so the OIDC login path
 * never fails because of a missing photo — we log the reason and return null.
 */
@ApplicationScoped
public class AvatarFetcher {

    private static final Logger LOG = Logger.getLogger(AvatarFetcher.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_BYTES = 500_000;

    public record Avatar(byte[] bytes, String contentType, String etag) {}

    /** A picture URL may redirect; a few hops cover every real CDN. */
    static final int MAX_REDIRECTS = 3;

    @Inject HttpFetcher http;
    @Inject HostResolver resolver;
    @Inject RoutedNetworks routed;

    /**
     * Calls an OIDC userinfo endpoint with the access token and pulls the
     * {@code picture} URL out. Used as a fallback when the ID-Token doesn't
     * carry it (some Google Workspace setups only put essential claims in the
     * token and expose the rest via userinfo).
     */
    public String fetchUserinfoPictureUrl(String userinfoEndpoint, String accessToken) {
        if (userinfoEndpoint == null || accessToken == null) return null;
        try {
            HttpFetcher.Response r = http.get(userinfoEndpoint,
                    Map.of("authorization", "Bearer " + accessToken));
            if (r.status() != 200) {
                LOG.infof("userinfo %s returned HTTP %d", userinfoEndpoint, r.status());
                return null;
            }
            JsonNode body = JSON.readTree(r.body());
            String pic = body.path("picture").asText(null);
            if (pic == null || pic.isBlank()) {
                LOG.infof("userinfo %s did not include 'picture'", userinfoEndpoint);
                return null;
            }
            return pic;
        } catch (Exception ex) {
            LOG.infof("userinfo fetch failed (%s): %s", userinfoEndpoint, ex.getMessage());
            return null;
        }
    }

    /** MS Graph: GET /me/photo/$value with the OIDC access token. */
    public Avatar fetchMicrosoft(String accessToken) {
        try {
            HttpFetcher.Response r = http.get(
                    "https://graph.microsoft.com/v1.0/me/photo/$value",
                    Map.of("authorization", "Bearer " + accessToken));
            if (r.status() == 404 || r.status() == 403) return null;  // user has no photo / no consent
            if (r.status() != 200) {
                LOG.debugf("MS Graph photo returned HTTP %d", r.status());
                return null;
            }
            if (r.body().length > MAX_BYTES) {
                LOG.debugf("MS Graph photo too large (%d bytes), skipping", r.body().length);
                return null;
            }
            return sniffed(r.body(), "MS Graph photo");
        } catch (Exception ex) {
            LOG.debugf("MS Graph photo fetch failed: %s", ex.getMessage());
            return null;
        }
    }

    /**
     * Google and custom providers: the {@code picture} claim is a URL we GET.
     *
     * <p>That URL is the identity provider's word, and on a self-run provider
     * the user's (oidc-avatar-ssrf-xss). Every hop — the first and each
     * redirect — passes {@link UrlFetchPolicy} before it is requested, and
     * the client never follows a redirect on its own. What comes back is kept
     * only if its bytes are an image, under the type the bytes have.
     */
    public Avatar fetchByUrl(String url) {
        if (url == null || url.isBlank()) {
            LOG.infof("avatar by URL skipped: picture claim was null/blank");
            return null;
        }
        String current = url;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                java.net.URI uri = UrlFetchPolicy.requireFetchable(current, resolver, routed.cidrs());
                HttpFetcher.Response r = http.getWithoutRedirects(uri.toString(), null);
                if (r.status() >= 300 && r.status() < 400) {
                    String location = r.headers().get("location");
                    if (location == null || location.isBlank()) {
                        LOG.infof("avatar URL %s redirected without a Location", current);
                        return null;
                    }
                    current = uri.resolve(location.trim()).toString();
                    continue;
                }
                if (r.status() != 200) {
                    LOG.infof("avatar URL %s returned HTTP %d", current, r.status());
                    return null;
                }
                if (r.body().length > MAX_BYTES) {
                    LOG.infof("avatar URL %s too large (%d bytes)", current, r.body().length);
                    return null;
                }
                return sniffed(r.body(), "avatar URL " + current);
            }
            LOG.infof("avatar URL %s redirected more than %d times, giving up", url, MAX_REDIRECTS);
            return null;
        } catch (UrlFetchPolicy.Refused refused) {
            // WARN, not INFO: on a public provider this does not happen, so it
            // is either a misconfigured IdP or someone trying.
            LOG.warnf("avatar URL not fetched: %s", refused.getMessage());
            return null;
        } catch (Exception ex) {
            LOG.infof("avatar URL fetch failed (%s): %s", current, ex.getMessage());
            return null;
        }
    }

    /**
     * Gravatar: md5(lowercased trimmed email), 200x200, d=404 so we get a clean
     * "no avatar" signal instead of a default placeholder image.
     */
    public Avatar fetchGravatar(String email) {
        if (email == null || email.isBlank()) return null;
        String hash = md5Hex(email.trim().toLowerCase(Locale.ROOT));
        String url = "https://www.gravatar.com/avatar/" + hash + "?s=200&d=404";
        try {
            HttpFetcher.Response r = http.get(url, null);
            if (r.status() != 200) return null;
            if (r.body().length > MAX_BYTES) return null;
            return sniffed(r.body(), "Gravatar");
        } catch (Exception ex) {
            LOG.debugf("Gravatar fetch failed: %s", ex.getMessage());
            return null;
        }
    }

    /**
     * Keeps the bytes only if they are an image, under the type they are — the
     * remote {@code Content-Type} is a claim, and an HTML answer stored with
     * it was served back as {@code text/html} on Islandr's origin.
     */
    private static Avatar sniffed(byte[] body, String what) {
        String type = AvatarImage.sniffType(body);
        if (type == null) {
            LOG.infof("%s is not a PNG, JPEG, GIF or WebP image — not stored", what);
            return null;
        }
        return new Avatar(body, type, etagFor(body));
    }

    static String md5Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception ex) {
            throw new IllegalStateException("MD5 unavailable", ex);
        }
    }

    /** Weak ETag based on SHA-256 of the bytes — stable across calls, fits in our 64-char column. */
    static String etagFor(byte[] body) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(body);
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (Exception ex) {
            return Integer.toHexString(java.util.Arrays.hashCode(body));
        }
    }
}
