package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.auth.ClientAddress;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Seeds the trusted-proxy settings from the environment on a fresh install
 * (issue #80), so the very first failed login already logs the address a ban
 * could act on rather than the proxy's.
 *
 * <p><b>Seed, not override.</b> It writes only while the setting is still
 * empty. Once an admin has set a value in the console — including deliberately
 * clearing it back to "nobody" and then setting it again — the database is the
 * source of truth and this never touches it. An operator who edits
 * {@code /etc/default/islandr} and restarts expecting the console to change
 * would otherwise get a silent overwrite of a security-relevant value, which is
 * the worse failure of the two.
 *
 * <p>Nothing is guessed. A hub bound to loopback is a strong hint that a proxy
 * sits in front, but it is not proof, and trusting an address that nothing is
 * actually behind is exactly the mistake this whole mechanism exists to
 * prevent — so with the variable unset, nobody is trusted.
 */
@ApplicationScoped
public class TrustedProxyBootstrap {

    private static final Logger LOG = Logger.getLogger(TrustedProxyBootstrap.class);

    @ConfigProperty(name = "islandr.auth.trusted-proxies")
    Optional<String> seedTrustedProxies;

    @ConfigProperty(name = "islandr.auth.client-ip-header")
    Optional<String> seedClientIpHeader;

    @Inject SettingsService settings;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        String proxies = seedTrustedProxies.filter(s -> !s.isBlank()).orElse(null);
        String header = seedClientIpHeader.filter(s -> !s.isBlank()).orElse(null);
        if (proxies == null && header == null) return;

        Settings s = settings.get();
        if (proxies != null && (s.trustedProxies == null || s.trustedProxies.isBlank())) {
            try {
                s.trustedProxies = ClientAddress.validate(proxies);
                LOG.infof("seeded trusted proxies from the environment: %s", s.trustedProxies);
            } catch (IllegalArgumentException e) {
                // Refuse the value rather than store half of it: a partially
                // applied trust list is worse than none, because it looks
                // configured.
                LOG.errorf("ISLANDR_AUTH_TRUSTED_PROXIES is invalid and was ignored — %s. "
                        + "Set it in the Admin Console under Security.", e.getMessage());
            }
        }
        if (header != null && (s.clientIpHeader == null || s.clientIpHeader.isBlank())) {
            s.clientIpHeader = header.trim();
            LOG.infof("seeded client-address header from the environment: %s", s.clientIpHeader);
        }
    }
}
