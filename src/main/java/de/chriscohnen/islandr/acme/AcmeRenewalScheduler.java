package de.chriscohnen.islandr.acme;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Renews the ACME-managed certificate before it expires (ADR-0019, closing
 * R-153 for this mode). Runs on a fixed schedule, and once at boot as a
 * backstop (R-166): if the hub was down across an entire scheduled interval,
 * the boot check still catches a certificate that's now due, instead of
 * waiting for the next scheduled tick.
 *
 * <p>{@link AcmeService#issueCertificate()} already records success/failure
 * on {@code Settings} itself (surfaced in the Settings UI) — this class only
 * decides *when* to call it and logs the outcome; it never lets a failure
 * propagate to the scheduler or crash the boot sequence.
 */
@ApplicationScoped
public class AcmeRenewalScheduler {

    private static final Logger LOG = Logger.getLogger(AcmeRenewalScheduler.class);

    @Inject AcmeService acme;

    void onStart(@Observes StartupEvent ev) {
        checkAndRenew();
    }

    @Scheduled(every = "{islandr.acme.renewal-check-interval}")
    void scheduledCheck() {
        checkAndRenew();
    }

    void checkAndRenew() {
        try {
            if (!acme.renewalDue()) return;
            LOG.info("ACME: certificate due for issuance/renewal, starting");
            acme.issueCertificate();
        } catch (AcmeException e) {
            // Already recorded on Settings (acmeLastError) by AcmeService — the
            // Settings UI shows it; still log so it shows up in `journalctl`/
            // `docker logs` without an admin having to go check Settings first.
            LOG.warnf("ACME: renewal check failed: %s", e.getMessage());
        } catch (RuntimeException e) {
            LOG.errorf(e, "ACME: unexpected error during renewal check");
        }
    }
}
