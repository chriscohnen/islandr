package de.chriscohnen.islandr.network;

import de.chriscohnen.islandr.proxy.AdapterMode;
import de.chriscohnen.islandr.proxy.ContainerDetector;
import de.chriscohnen.islandr.proxy.ProxyClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Picks the {@link NetworkDiagnosticsAdapter} implementation at startup based on
 * {@code islandr.diag.mode} ({@code real}, {@code mock}, or {@code socket}) — same
 * resolution rule as {@link de.chriscohnen.islandr.wg.WgAdapterProducer} (design §8, D3):
 * an explicit value always wins, an unset value defaults to {@code socket} inside a
 * container and {@code mock} on a bare host.
 *
 * <p>A separate property from {@code islandr.wg.mode} rather than reusing it — a
 * deployment could plausibly want real {@code wg} but no diagnostics tooling (or vice
 * versa) — but the same socket path and container detection are reused, since it is
 * the same host proxy either way.
 */
@ApplicationScoped
public class NetworkDiagnosticsAdapterProducer {

    private static final Logger LOG = Logger.getLogger(NetworkDiagnosticsAdapterProducer.class);

    @ConfigProperty(name = "islandr.diag.mode")
    Optional<String> mode;

    @ConfigProperty(name = "islandr.use-sudo", defaultValue = "false")
    boolean useSudo;

    @ConfigProperty(name = "islandr.proxy.socket", defaultValue = "/run/islandr/proxy.sock")
    String proxySocket;

    @ConfigProperty(name = "islandr.proxy.timeout", defaultValue = "2s")
    Duration proxyTimeout;

    @Inject ContainerDetector containerDetector;

    @Produces
    @ApplicationScoped
    public NetworkDiagnosticsAdapter produce() {
        String resolved = AdapterMode.resolve(mode, containerDetector.inContainer());
        switch (resolved) {
            case "real":
                LOG.infof("NetworkDiagnosticsAdapter mode=real, useSudo=%s", useSudo);
                return new RealNetworkDiagnosticsAdapter(useSudo);
            case "socket":
                LOG.infof("NetworkDiagnosticsAdapter mode=socket — talking to host proxy at %s (timeout %s)", proxySocket, proxyTimeout);
                return new SocketNetworkDiagnosticsAdapter(new ProxyClient(Path.of(proxySocket), proxyTimeout));
            default:
                LOG.info("NetworkDiagnosticsAdapter mode=mock — using in-memory implementation");
                return new MockNetworkDiagnosticsAdapter();
        }
    }
}
