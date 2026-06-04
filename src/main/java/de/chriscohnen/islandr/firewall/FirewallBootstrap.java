package de.chriscohnen.islandr.firewall;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * On Quarkus startup: recompute the ruleset from the DB and apply it. This
 * makes the DB the unambiguous source of truth — a reboot of the hub VM
 * leaves you with exactly the state the admin had configured, regardless
 * of what someone might have typed at the {@code nft} CLI in the meantime.
 *
 * <p>The recompute is silent on the happy path. Failures go to the
 * {@link FirewallState#stderrText} field so the dashboard surfaces them.
 *
 * <p>Disable with {@code islandr.firewall.boot-apply=false} (test profile
 * sets this so unit tests don't randomly apply rulesets during boot).
 */
@ApplicationScoped
public class FirewallBootstrap {

    private static final Logger LOG = Logger.getLogger(FirewallBootstrap.class);

    @Inject RulesetService rulesets;

    @ConfigProperty(name = "islandr.firewall.boot-apply", defaultValue = "true")
    boolean bootApply;

    void onStart(@Observes StartupEvent ev) {
        if (!bootApply) {
            LOG.info("firewall boot-apply disabled — skipping nftables sync at startup");
            return;
        }
        try {
            FirewallState state = rulesets.recomputeAndApply("system:boot");
            LOG.infof("firewall boot-apply: status=%s ruleCount=%d",
                    state.lastStatus, state.ruleCount);
        } catch (Exception ex) {
            // recomputeAndApply itself swallows nft errors and records them
            // in FirewallState. Anything that bubbles up here would be a
            // pre-application failure (DB unreachable, builder bug, …) and
            // shouldn't kill the JVM — better to come up degraded with the
            // admin able to investigate.
            LOG.errorf(ex, "firewall boot-apply failed");
        }
    }
}
