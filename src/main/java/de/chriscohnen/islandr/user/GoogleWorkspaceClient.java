package de.chriscohnen.islandr.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.identity.HttpFetcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Calls the Google Admin SDK Directory API using a service account JWT.
 * No Google client library — uses the existing HttpFetcher and standard Java crypto.
 *
 * <p>The service account must have domain-wide delegation enabled in the Google
 * Admin Console, with the scope {@code admin.directory.user.readonly}.
 */
@ApplicationScoped
public class GoogleWorkspaceClient {

    private static final Logger LOG = Logger.getLogger(GoogleWorkspaceClient.class);
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String DIRECTORY_SCOPE =
            "https://www.googleapis.com/auth/admin.directory.user.readonly";
    private static final String DIRECTORY_URL =
            "https://admin.googleapis.com/admin/directory/v1/users";

    @Inject HttpFetcher http;
    @Inject ObjectMapper mapper;

    public record WorkspaceUser(
            String email,
            String name,
            boolean suspended,
            String avatarUrl
    ) {}

    /**
     * Lists all users in the domain derived from {@code impersonationEmail}.
     * Up to 500 users per call (one page). The service account JSON is the
     * raw content of the Google Cloud Console key file.
     */
    public List<WorkspaceUser> listUsers(String serviceAccountJson, String impersonationEmail)
            throws Exception {
        String accessToken = fetchAccessToken(serviceAccountJson, impersonationEmail);
        String domain = impersonationEmail.substring(impersonationEmail.indexOf('@') + 1);

        List<WorkspaceUser> result = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder(DIRECTORY_URL)
                    .append("?domain=").append(domain)
                    .append("&maxResults=500")
                    .append("&projection=basic")
                    .append("&fields=users(primaryEmail,name/fullName,suspended,thumbnailPhotoUrl),nextPageToken");
            if (pageToken != null) url.append("&pageToken=").append(pageToken);

            HttpFetcher.Response r = http.get(url.toString(), Map.of(
                    "Authorization", "Bearer " + accessToken));
            if (r.status() != 200) {
                throw new RuntimeException("Directory API returned " + r.status() + ": " + r.text());
            }

            JsonNode root = mapper.readTree(r.body());
            JsonNode users = root.path("users");
            if (users.isArray()) {
                for (JsonNode u : users) {
                    result.add(new WorkspaceUser(
                            u.path("primaryEmail").asText(null),
                            u.path("name").path("fullName").asText(null),
                            u.path("suspended").asBoolean(false),
                            u.path("thumbnailPhotoUrl").asText(null)
                    ));
                }
            }
            pageToken = root.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isBlank());

        return result;
    }

    private String fetchAccessToken(String serviceAccountJson, String impersonationEmail)
            throws Exception {
        JsonNode sa = mapper.readTree(serviceAccountJson);
        String clientEmail = sa.path("client_email").asText(null);
        String rawPem     = sa.path("private_key").asText(null);
        if (clientEmail == null || rawPem == null) {
            throw new IllegalArgumentException("service account JSON missing client_email or private_key");
        }

        PrivateKey privateKey = loadPrivateKey(rawPem);
        String jwt = buildJwt(clientEmail, impersonationEmail, privateKey);

        HttpFetcher.Response r = http.postForm(TOKEN_URL,
                Map.of("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer",
                       "assertion", jwt),
                Map.of());
        if (r.status() != 200) {
            throw new RuntimeException("token endpoint returned " + r.status() + ": " + r.text());
        }
        return mapper.readTree(r.body()).path("access_token").asText(null);
    }

    private static PrivateKey loadPrivateKey(String pem) throws Exception {
        String stripped = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(stripped);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private static String buildJwt(String clientEmail, String impersonationEmail,
                                    PrivateKey privateKey) throws Exception {
        long now = Instant.now().getEpochSecond();

        String header = base64url("""
                {"alg":"RS256","typ":"JWT"}""".stripIndent().trim().getBytes(StandardCharsets.UTF_8));
        String claim  = base64url(("""
                {"iss":"%s","scope":"%s","aud":"%s","iat":%d,"exp":%d,"sub":"%s"}"""
                .formatted(clientEmail, DIRECTORY_SCOPE, TOKEN_URL, now, now + 3600, impersonationEmail))
                .getBytes(StandardCharsets.UTF_8));

        String signingInput = header + "." + claim;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64url(sig.sign());
    }

    private static String base64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
