package de.chriscohnen.islandr.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuditDiff}. Pure Map → JSON; no JPA, no CDI.
 */
class AuditDiffTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void build_create_emitsAfterOnly() throws Exception {
        String s = AuditDiff.build(null, Map.of("name", "alice", "enabled", true));
        JsonNode n = JSON.readTree(s);
        assertThat(n.has("before")).isFalse();
        assertThat(n.get("after").get("name").asText()).isEqualTo("alice");
        assertThat(n.get("after").get("enabled").asBoolean()).isTrue();
    }

    @Test
    void build_delete_emitsBeforeOnly() throws Exception {
        String s = AuditDiff.build(Map.of("name", "bob"), null);
        JsonNode n = JSON.readTree(s);
        assertThat(n.has("after")).isFalse();
        assertThat(n.get("before").get("name").asText()).isEqualTo("bob");
    }

    @Test
    void build_update_emitsOnlyChangedKeys() throws Exception {
        Map<String, Object> before = Map.of("name", "alice", "enabled", false, "email", "a@x");
        Map<String, Object> after  = Map.of("name", "alice", "enabled", true,  "email", "a@x");
        String s = AuditDiff.build(before, after);
        JsonNode n = JSON.readTree(s);
        assertThat(n.get("before").has("name")).isFalse();
        assertThat(n.get("before").has("email")).isFalse();
        assertThat(n.get("before").get("enabled").asBoolean()).isFalse();
        assertThat(n.get("after").get("enabled").asBoolean()).isTrue();
    }

    @Test
    void build_noChange_returnsNull() {
        Map<String, Object> same = Map.of("name", "alice");
        assertThat(AuditDiff.build(same, same)).isNull();
    }

    @Test
    void build_redactsSensitiveKeys() throws Exception {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("clientId", "abc");
        after.put("clientSecret", "super-secret-value");
        after.put("private_key_pem", "PRIV...");
        after.put("password", "hunter2");
        String s = AuditDiff.build(null, after);
        // Sensitive values must be replaced; the KEY may still be in the JSON.
        assertThat(s).doesNotContain("super-secret-value");
        assertThat(s).doesNotContain("PRIV...");
        assertThat(s).doesNotContain("hunter2");
        // Non-sensitive key is preserved as-is.
        JsonNode n = JSON.readTree(s);
        assertThat(n.get("after").get("clientId").asText()).isEqualTo("abc");
        assertThat(n.get("after").get("clientSecret").asText()).isEqualTo("***");
        assertThat(n.get("after").get("password").asText()).isEqualTo("***");
    }

    @Test
    void isSensitive_matchesNeedlesCaseInsensitively() {
        assertThat(AuditDiff.isSensitive("clientSecret")).isTrue();
        assertThat(AuditDiff.isSensitive("client_secret")).isTrue();
        assertThat(AuditDiff.isSensitive("CLIENT_SECRET")).isTrue();
        assertThat(AuditDiff.isSensitive("PrivateKey")).isTrue();
        assertThat(AuditDiff.isSensitive("password_hash")).isTrue();
        assertThat(AuditDiff.isSensitive("publicKey")).isFalse();
        assertThat(AuditDiff.isSensitive("name")).isFalse();
        assertThat(AuditDiff.isSensitive(null)).isFalse();
    }

    @Test
    void details_wrapsUnderDetailsKey_andRedacts() throws Exception {
        String s = AuditDiff.details(Map.of(
                "provider", "google",
                "password", "leak-me-please"));
        JsonNode n = JSON.readTree(s);
        assertThat(n.get("details").get("provider").asText()).isEqualTo("google");
        assertThat(n.get("details").get("password").asText()).isEqualTo("***");
    }
}
