package de.chriscohnen.islandr.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a Microsoft/Entra ID provider configuration field by field and names
 * the one at fault — issue #81. Without it the first wrong value surfaces much
 * later as a login failure whose message mentions neither the field nor the
 * cause, and the operator is left guessing which of four copied values is the
 * bad one.
 *
 * <p>Each check is chosen so that its answer settles exactly one field:
 * <ul>
 *   <li><b>Tenant</b> — the discovery document resolves or it does not, and it
 *       needs no credentials, so nothing else can be blamed for a failure.</li>
 *   <li><b>Client ID</b> — Entra answers an unknown application with
 *       {@code AADSTS700016}, which is unambiguous.</li>
 *   <li><b>Client secret</b> — a {@code client_credentials} request separates
 *       "wrong" ({@code AADSTS7000215}) from "expired" ({@code AADSTS7000222}).
 *       The two demand different actions and otherwise look identical.</li>
 *   <li><b>Redirect URI</b> — {@code AADSTS50011} means the URI Islandr sends
 *       is not registered on the app, rather than "login failed".</li>
 * </ul>
 *
 * <p>These are the only outbound calls Islandr makes to Microsoft outside a
 * login, and they happen when an admin presses the button — never on a timer.
 */
@ApplicationScoped
public class MicrosoftConfigCheck {

    /** Entra puts its error code in the body of both JSON and HTML responses. */
    private static final Pattern AADSTS = Pattern.compile("AADSTS\\d{4,10}");

    public static final String OK = "ok";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    @Inject HttpFetcher http;

    /**
     * @param field   the configuration field this check settles
     * @param status  {@link #OK}, {@link #FAILED} or {@link #SKIPPED}
     * @param code    the Entra error code when there is one, else null
     * @param message a sentence naming what is wrong, in the admin's terms
     */
    public record Check(String field, String status, String code, String message) {}

    public record Report(boolean ok, String redirectUri, List<Check> checks) {}

    public Report run(OidcProvider p, String redirectUri) {
        List<Check> checks = new ArrayList<>();

        boolean tenantOk = checkTenant(p, checks);
        boolean clientOk = checkClientId(p, redirectUri, tenantOk, checks);
        checkSecret(p, tenantOk, checks);
        checkRedirectUri(p, redirectUri, tenantOk && clientOk, checks);

        boolean ok = checks.stream().allMatch(c -> OK.equals(c.status()));
        return new Report(ok, redirectUri, checks);
    }

    // -- tenant ---------------------------------------------------------------

    private boolean checkTenant(OidcProvider p, List<Check> checks) {
        if (isBlank(p.tenantId)) {
            checks.add(new Check("tenantId", FAILED, null, "No tenant ID configured."));
            return false;
        }
        String url = "https://login.microsoftonline.com/" + enc(p.tenantId.trim())
                + "/v2.0/.well-known/openid-configuration";
        try {
            HttpFetcher.Response r = http.get(url, Map.of("Accept", "application/json"));
            if (r.status() == 200) {
                checks.add(new Check("tenantId", OK, null, "Tenant resolves."));
                return true;
            }
            checks.add(new Check("tenantId", FAILED, codeIn(r.text()),
                    "Microsoft does not know this tenant (HTTP " + r.status()
                            + "). Check the Directory (tenant) ID."));
            return false;
        } catch (Exception e) {
            checks.add(new Check("tenantId", FAILED, null,
                    "Could not reach Microsoft: " + e.getMessage()));
            return false;
        }
    }

    // -- client id ------------------------------------------------------------

    private boolean checkClientId(OidcProvider p, String redirectUri, boolean tenantOk, List<Check> checks) {
        if (!tenantOk) {
            checks.add(new Check("clientId", SKIPPED, null, "Not checked — the tenant has to resolve first."));
            return false;
        }
        if (isBlank(p.clientId)) {
            checks.add(new Check("clientId", FAILED, null, "No client ID configured."));
            return false;
        }
        String body = probeAuthorize(p, redirectUri);
        if (body == null) {
            checks.add(new Check("clientId", SKIPPED, null, "Could not reach Microsoft."));
            return false;
        }
        String code = codeIn(body);
        if ("AADSTS700016".equals(code)) {
            checks.add(new Check("clientId", FAILED, code,
                    "Microsoft does not know this application in that tenant. "
                            + "Check the Application (client) ID, and that it belongs to this tenant."));
            return false;
        }
        checks.add(new Check("clientId", OK, null, "Application found in the tenant."));
        return true;
    }

    // -- client secret --------------------------------------------------------

    private void checkSecret(OidcProvider p, boolean tenantOk, List<Check> checks) {
        if (!tenantOk) {
            checks.add(new Check("clientSecret", SKIPPED, null, "Not checked — the tenant has to resolve first."));
            return;
        }
        if (isBlank(p.clientSecret)) {
            checks.add(new Check("clientSecret", FAILED, null, "No client secret configured."));
            return;
        }
        String token = "https://login.microsoftonline.com/" + enc(p.tenantId.trim()) + "/oauth2/v2.0/token";
        String body;
        try {
            HttpFetcher.Response r = http.postForm(token, Map.of(
                    "grant_type", "client_credentials",
                    "client_id", p.clientId == null ? "" : p.clientId.trim(),
                    "client_secret", p.clientSecret,
                    "scope", "https://graph.microsoft.com/.default"), Map.of());
            if (r.status() == 200) {
                checks.add(new Check("clientSecret", OK, null, "Secret accepted."));
                return;
            }
            body = r.text();
        } catch (Exception e) {
            checks.add(new Check("clientSecret", SKIPPED, null,
                    "Could not reach Microsoft: " + e.getMessage()));
            return;
        }
        String code = codeIn(body);
        checks.add(new Check("clientSecret", FAILED, code, switch (code == null ? "" : code) {
            // The two cases that demand different actions and otherwise look
            // identical to the operator.
            case "AADSTS7000215" -> "The secret is wrong. The Entra secrets table shows a Value and a "
                    + "Secret ID side by side — Islandr needs the Value, which is only shown once, at creation.";
            case "AADSTS7000222" -> "The secret has expired. Create a new one in Entra and paste its Value here.";
            case "AADSTS700016" -> "Microsoft does not know this application — the client ID is wrong, "
                    + "so the secret could not be checked.";
            case "AADSTS7000112" -> "The application is disabled in Entra.";
            default -> "Microsoft rejected the secret" + (code == null ? "" : " (" + code + ")") + ".";
        }));
    }

    // -- redirect uri ---------------------------------------------------------

    private void checkRedirectUri(OidcProvider p, String redirectUri, boolean precondition, List<Check> checks) {
        if (!precondition) {
            checks.add(new Check("redirectUri", SKIPPED, null,
                    "Not checked — tenant and client ID have to be right first."));
            return;
        }
        String body = probeAuthorize(p, redirectUri);
        if (body == null) {
            checks.add(new Check("redirectUri", SKIPPED, null, "Could not reach Microsoft."));
            return;
        }
        String code = codeIn(body);
        if ("AADSTS50011".equals(code)) {
            checks.add(new Check("redirectUri", FAILED, code,
                    "This URI is not registered on the application. Add it in Entra under "
                            + "Authentication → Web → Redirect URIs, exactly as shown: " + redirectUri));
            return;
        }
        checks.add(new Check("redirectUri", OK, null, "Registered on the application."));
    }

    /**
     * One authorize probe, reused by the client-ID and redirect-URI checks.
     * Nothing is signed in here: Entra answers a well-formed request with its
     * sign-in page and a malformed one with an error page, and both carry the
     * code in the body. Returns null when the request could not be made at all.
     */
    private String probeAuthorize(OidcProvider p, String redirectUri) {
        String url = "https://login.microsoftonline.com/" + enc(p.tenantId.trim()) + "/oauth2/v2.0/authorize"
                + "?client_id=" + enc(p.clientId == null ? "" : p.clientId.trim())
                + "&response_type=code"
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc("openid profile email")
                + "&response_mode=query"
                // prompt=none keeps the probe from ever landing a human on a
                // sign-in form: Entra answers with an error instead, and the
                // errors we are looking for come before that check anyway.
                + "&prompt=none"
                + "&state=islandr-config-check";
        try {
            HttpFetcher.Response r = http.get(url, Map.of("Accept", "text/html"));
            return r.text();
        } catch (Exception e) {
            return null;
        }
    }

    // -- helpers --------------------------------------------------------------

    static String codeIn(String body) {
        if (body == null) return null;
        Matcher m = AADSTS.matcher(body);
        return m.find() ? m.group() : null;
    }

    /**
     * A secret <em>Value</em> is never a UUID; a Secret <em>ID</em> always is.
     * Catching it at input time is the cheapest possible fix for the single
     * most common Entra setup mistake — no network call, and the message can
     * name the field the operator actually wants.
     */
    public static boolean looksLikeSecretId(String secret) {
        return secret != null
                && secret.trim().matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
