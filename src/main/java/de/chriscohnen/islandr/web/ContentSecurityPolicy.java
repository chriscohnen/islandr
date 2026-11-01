package de.chriscohnen.islandr.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Content-Security-Policy, built from the {@code index.html} that is
 * actually served. The page carries two inline scripts (the theme, set before
 * the first paint, and the import map); each is allowed by its own SHA-256 hash
 * rather than by {@code 'unsafe-inline'}. The hashes are computed here, at start,
 * so that editing either script cannot leave a stale hash behind that breaks the page.
 *
 * <p>What stays loose, and why: {@code 'unsafe-eval'} because Vue compiles the
 * templates in the browser, {@code 'wasm-unsafe-eval'} for the RDP client,
 * {@code style-src 'unsafe-inline'} because the templates carry {@code style}
 * attributes.
 */
public final class ContentSecurityPolicy {

    static final String REPORT_PATH = "/api/v1/csp-report";

    private static final Pattern INLINE_SCRIPT = Pattern.compile(
            "<script(?:\\s+type=\"importmap\")?\\s*>(.*?)</script>", Pattern.DOTALL);

    private ContentSecurityPolicy() {}

    /** The inline script bodies of {@code html}: those without a {@code src} attribute. */
    static List<String> inlineScripts(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = INLINE_SCRIPT.matcher(html);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static String hashOf(String script) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
            return "'sha256-" + Base64.getEncoder().encodeToString(d) + "'";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param indexHtml the served {@code index.html}; null or without inline scripts yields a policy without hashes */
    static String build(String indexHtml) {
        StringBuilder scripts = new StringBuilder("script-src 'self' 'unsafe-eval' 'wasm-unsafe-eval'");
        if (indexHtml != null) {
            for (String s : inlineScripts(indexHtml)) scripts.append(' ').append(hashOf(s));
        }
        return "default-src 'self'; " + scripts
                + "; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self';"
                + " font-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none';"
                + " form-action 'self'; report-uri " + REPORT_PATH;
    }
}
