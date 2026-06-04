package de.chriscohnen.islandr.wg;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Picks the {@link WgAdapter} implementation at startup based on
 * {@code islandr.wg.mode} ({@code real} or {@code mock}).
 *
 * <p>Default in dev/test is {@code mock} so the app starts on any machine
 * without a kernel wg interface. The Hub VM sets {@code islandr.wg.mode=real}
 * via env var or {@code application.properties} override.
 */
@ApplicationScoped
public class WgAdapterProducer {

    private static final Logger LOG = Logger.getLogger(WgAdapterProducer.class);

    @ConfigProperty(name = "islandr.wg.mode", defaultValue = "mock")
    String mode;

    @ConfigProperty(name = "islandr.use-sudo", defaultValue = "false")
    boolean useSudo;

    @Produces
    @ApplicationScoped
    public WgAdapter produce() {
        if ("real".equalsIgnoreCase(mode)) {
            LOG.infof("WgAdapter mode=real, useSudo=%s — using ProcessBuilder against `wg` CLI", useSudo);
            return new RealWgAdapter(useSudo);
        }
        LOG.info("WgAdapter mode=mock — using in-memory implementation (no real WireGuard interaction)");
        return new MockWgAdapter();
    }
}
