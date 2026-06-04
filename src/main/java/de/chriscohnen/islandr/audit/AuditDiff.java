package de.chriscohnen.islandr.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the JSON blob written to {@link AuditLog#metaJson}.
 *
 * <p>Two responsibilities:
 * <ul>
 *   <li>Diff: from two maps {@code before}/{@code after}, keep only the keys
 *       whose value actually changed. A create has before=null, a delete has
 *       after=null — both are handled.
 *   <li>Redact: replace the value of any sensitive key with the literal
 *       {@code "***"} so secrets never reach the audit table. Keys are matched
 *       case-insensitively against a small allowlist (any key containing
 *       {@code secret}, {@code password}, {@code private_key} etc.).
 * </ul>
 *
 * Callers don't construct the JSON themselves — they call {@link #build} with
 * raw maps and get back the string that goes into the column.
 */
public final class AuditDiff {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Substrings that mark a key as sensitive. Matched case-insensitively on
     * the key, not the value. Kept small — false positives ("publicKey",
     * "secretary") are unlikely in our domain.
     */
    private static final Set<String> REDACT_NEEDLES = Set.of(
            "secret",
            "password",
            "private_key",
            "privatekey",
            "client_secret",
            "clientsecret"
    );

    /** Placeholder written in place of redacted values. */
    static final String REDACTED = "***";

    /**
     * Returns the JSON string to store, or {@code null} if there's nothing
     * worth logging (no changes between before/after).
     */
    public static String build(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> bd = diffBefore(before, after);
        Map<String, Object> ad = diffAfter(before, after);
        if (bd != null && !bd.isEmpty()) result.put("before", redact(bd));
        if (ad != null && !ad.isEmpty()) result.put("after", redact(ad));
        if (result.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            // Diagnostic — losing the meta should not lose the row, the
            // AuditService writes null in this column and the audit entry
            // still records who did what.
            return null;
        }
    }

    /**
     * Convenience for actions that don't fit the before/after model — just a
     * single {@code details} payload (e.g. a login attempt). Same redaction
     * rules apply.
     */
    public static String details(Map<String, Object> details) {
        if (details == null || details.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(Map.of("details", redact(details)));
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    // -- internals ------------------------------------------------------------

    private static Map<String, Object> diffBefore(Map<String, Object> before, Map<String, Object> after) {
        if (before == null) return null;
        if (after == null) return new LinkedHashMap<>(before);  // delete — log full before
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : before.entrySet()) {
            Object newVal = after.get(e.getKey());
            if (!Objects.equals(e.getValue(), newVal)) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static Map<String, Object> diffAfter(Map<String, Object> before, Map<String, Object> after) {
        if (after == null) return null;
        if (before == null) return new LinkedHashMap<>(after);  // create — log full after
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : after.entrySet()) {
            Object oldVal = before.get(e.getKey());
            if (!Objects.equals(oldVal, e.getValue())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    static Map<String, Object> redact(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), isSensitive(e.getKey()) ? REDACTED : e.getValue());
        }
        return out;
    }

    static boolean isSensitive(String key) {
        if (key == null) return false;
        String k = key.toLowerCase();
        for (String needle : REDACT_NEEDLES) {
            if (k.contains(needle)) return true;
        }
        return false;
    }

    private AuditDiff() {}
}
