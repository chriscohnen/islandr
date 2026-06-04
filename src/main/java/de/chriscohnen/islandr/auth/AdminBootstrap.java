package de.chriscohnen.islandr.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * ENV-driven bootstrap admin. Reads {@code ISLANDR_ADMIN_USER} and
 * {@code ISLANDR_ADMIN_PASSWORD} once at startup, holds them in memory.
 * Password comparison is constant-time on a SHA-256 hash so timing leaks
 * don't reveal length or prefix.
 *
 * <p>If no password is configured, the admin login is disabled (status 503 from
 * the auth endpoint). This is deliberate: defaulting to a known credential
 * in containers would be worse than a startup banner shouting at the operator.
 */
@ApplicationScoped
public class AdminBootstrap {

    private static final Logger LOG = Logger.getLogger(AdminBootstrap.class);

    @ConfigProperty(name = "islandr.admin.user", defaultValue = "admin")
    String configuredUser;

    @ConfigProperty(name = "islandr.admin.password")
    Optional<String> configuredPassword;

    private byte[] passwordHash;

    void onStart(@Observes StartupEvent ev) {
        if (configuredPassword.isEmpty() || configuredPassword.get().isBlank()) {
            LOG.warn("ISLANDR_ADMIN_PASSWORD is not set — local admin login is disabled. "
                    + "Set it via env var to enable the bootstrap admin.");
            passwordHash = null;
            return;
        }
        passwordHash = sha256(configuredPassword.get());
        LOG.infof("Local admin login enabled for user '%s' (password set via env).", configuredUser);
    }

    public boolean isEnabled() {
        return passwordHash != null;
    }

    public String userName() {
        return configuredUser;
    }

    public boolean matches(String user, String password) {
        if (!isEnabled()) return false;
        if (user == null || password == null) return false;
        if (!configuredUser.equals(user)) return false;
        byte[] candidate = sha256(password);
        return MessageDigest.isEqual(candidate, passwordHash);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
