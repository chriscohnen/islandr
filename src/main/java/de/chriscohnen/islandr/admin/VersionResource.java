package de.chriscohnen.islandr.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.identity.HttpFetcher;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;

@Path("/api/v1/version")
@Produces(MediaType.APPLICATION_JSON)
public class VersionResource {

    private static final Logger LOG = Logger.getLogger(VersionResource.class);
    private static final String RELEASES_URL =
            "https://api.github.com/repos/chriscohnen/islandr/releases/latest";

    @Inject HttpFetcher http;
    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    /**
     * On-demand GitHub release check. Never cached, never polled —
     * only called when the admin explicitly clicks the button.
     */
    @GET
    @Path("/check")
    public VersionCheckResponse check(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        try {
            HttpFetcher.Response r = http.get(RELEASES_URL, Map.of(
                    "Accept", "application/vnd.github+json",
                    "X-GitHub-Api-Version", "2022-11-28",
                    "User-Agent", "islandr/" + appVersion));
            if (r.status() != 200) {
                LOG.warnf("GitHub releases API returned %d", r.status());
                return VersionCheckResponse.error(appVersion, "GitHub API returned " + r.status());
            }
            var node = mapper.readTree(r.body());
            String tag = node.path("tag_name").asText(null);
            String url = node.path("html_url").asText(null);
            boolean upToDate = tag != null && semverCompare(appVersion, tag) >= 0;
            return new VersionCheckResponse(appVersion, tag, upToDate, url, null);
        } catch (Exception e) {
            LOG.warnf("version check failed: %s", e.getMessage());
            return VersionCheckResponse.error(appVersion, "GitHub nicht erreichbar");
        }
    }

    /** Strip leading 'v' so "v0.9.0" and "0.9.0" compare equal. */
    private static String strip(String v) {
        return (v != null && v.startsWith("v")) ? v.substring(1) : v;
    }

    /**
     * Semantic version comparison (major.minor.patch only; ignores pre-release suffix).
     * Returns negative if a < b, 0 if equal, positive if a > b.
     */
    private static int semverCompare(String a, String b) {
        String[] pa = strip(a).split("[.\\-]", 4);
        String[] pb = strip(b).split("[.\\-]", 4);
        for (int i = 0; i < 3; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    public record VersionCheckResponse(
            String current,
            String latest,
            Boolean upToDate,
            String releaseUrl,
            String error
    ) {
        static VersionCheckResponse error(String current, String msg) {
            return new VersionCheckResponse(current, null, null, null, msg);
        }
    }
}
