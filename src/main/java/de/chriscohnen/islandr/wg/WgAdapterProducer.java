package de.chriscohnen.islandr.wg;

import de.chriscohnen.islandr.proxy.AdapterMode;
import de.chriscohnen.islandr.proxy.ContainerDetector;
import de.chriscohnen.islandr.proxy.ProxyClient;
import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Picks the {@link WgAdapter} implementation at startup based on
 * {@code islandr.wg.mode} ({@code real}, {@code mock}, or {@code socket}).
 *
 * <p>Mode resolution (design §8, D3): an explicit config value always wins; an
 * unset value defaults to {@code socket} inside a container and {@code mock} on a
 * bare host. "In a container" is only a fallback default, never an override — so a
 * developer running the container for UI work can still force {@code mock}.
 *
 * <ul>
 *   <li>{@code real} — {@link RealWgAdapter}, shells out to {@code wg} on the Hub VM.
 *   <li>{@code mock} — {@link MockWgAdapter}, in-memory, for dev/CI and the demo image.
 *   <li>{@code socket} — {@link SocketWgAdapter}, talks to the host {@code islandr-proxy}
 *       over a Unix socket (ADR-0012).
 * </ul>
 */
@ApplicationScoped
public class WgAdapterProducer {

    private static final Logger LOG = Logger.getLogger(WgAdapterProducer.class);

    @ConfigProperty(name = "islandr.wg.mode")
    Optional<String> mode;

    @ConfigProperty(name = "islandr.use-sudo", defaultValue = "false")
    boolean useSudo;

    @ConfigProperty(name = "islandr.proxy.socket", defaultValue = "/run/islandr/proxy.sock")
    String proxySocket;

    @ConfigProperty(name = "islandr.proxy.timeout", defaultValue = "2s")
    Duration proxyTimeout;

    @Inject SettingsService settings;

    @Inject ContainerDetector containerDetector;

    @Produces
    @ApplicationScoped
    public WgAdapter produce() {
        String resolved = AdapterMode.resolve(mode, containerDetector.inContainer());
        switch (resolved) {
            case "real":
                LOG.infof("WgAdapter mode=real, useSudo=%s — wrapped with DryRunWgAdapter (checks settings at runtime)", useSudo);
                return new DryRunWgAdapter(new RealWgAdapter(useSudo), settings);
            case "socket":
                LOG.infof("WgAdapter mode=socket — talking to host proxy at %s (timeout %s)", proxySocket, proxyTimeout);
                return new SocketWgAdapter(new ProxyClient(Path.of(proxySocket), proxyTimeout));
            default:
                LOG.info("WgAdapter mode=mock — using in-memory implementation (no real WireGuard interaction)");
                return new MockWgAdapter();
        }
    }
}
