package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.audit.AuditService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * The escape hatch behind the security keys: clears every credential of the
 * local recovery admin, so a lost or broken authenticator costs a shell
 * session rather than the instance.
 *
 * <p><b>Why this exists at all.</b> The recovery admin is the break-glass
 * account for when OIDC is unavailable. Putting a key in front of it converts
 * a login risk into a lockout risk — on a machine that by definition sits
 * somewhere else. ADR-0028 answers that with this command and counts it as the
 * mitigation for R-188; it was the one part of that decision never built, so
 * the risk was answered on paper only.
 *
 * <p><b>Why an environment variable and not a CLI flag.</b> The binary is a
 * server and has no command mode — introducing one for this would mean a
 * second entry point and, in a container, a second process nobody starts
 * anyway. Every other operational switch on this product already works this
 * way ({@code ISLANDR_ADMIN_PASSWORD}, {@code ISLANDR_WG_MODE}), so an
 * operator learns nothing new, and it behaves identically for the native
 * binary and the image.
 *
 * <p><b>It does not widen the trust boundary.</b> Setting it requires shell
 * access on the hub, which already implies the database and the environment
 * file. What it adds is an audit entry, because the person who runs a reset is
 * not necessarily the person who notices it happened.
 *
 * <p><b>The one real hazard, and what is done about it.</b> Left set, the
 * variable clears freshly registered keys on every restart — silently, and
 * exactly when someone has just finished enrolling one. So it warns on every
 * boot while it is set, not only on the boot that did the work. A line in the
 * log costs nothing; that failure costs an afternoon.
 */
@ApplicationScoped
public class WebAuthnReset {

    private static final Logger LOG = Logger.getLogger(WebAuthnReset.class);

    @Inject AuditService audit;

    @ConfigProperty(name = "islandr.webauthn.reset", defaultValue = "false")
    boolean resetRequested;

    void onStart(@Observes StartupEvent ev) {
        if (!resetRequested) return;

        int removed = clearRecoveryAdminCredentials();

        // Warn regardless of whether anything was removed: the danger is the
        // variable still being set, not the number it found this time.
        LOG.warnf("ISLANDR_WEBAUTHN_RESET is set — removed %d security key(s) for the recovery "
                + "admin. UNSET IT NOW: while it stays set, every restart wipes the keys "
                + "registered since, including ones enrolled a minute ago.", removed);
    }

    /**
     * Removes every security key registered for the local recovery admin.
     *
     * <p>Separate from the startup hook so it is callable — and therefore
     * testable — without booting an application. Running it on a hub with
     * nothing registered is deliberately not an error: an operator reaching
     * for the escape hatch should not first have to establish whether it was
     * needed.
     *
     * @return how many credentials were removed
     */
    @Transactional
    public int clearRecoveryAdminCredentials() {
        long removed = WebAuthnCredential.delete("subject", WebAuthnCredential.LOCAL_ADMIN);
        audit.logEvent("system", "auth.webauthn_reset",
                "WebAuthnCredential:" + WebAuthnCredential.LOCAL_ADMIN,
                Map.of("removed", removed, "trigger", "ISLANDR_WEBAUTHN_RESET"));
        return (int) removed;
    }
}
