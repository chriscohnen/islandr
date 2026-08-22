package de.chriscohnen.islandr.network;

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
 * {@code islandr.diag.mode} ({@code real}, {@code mock}, or {@code socket}).
 *
 * <p>Deliberately <em>not</em> {@link de.chriscohnen.islandr.proxy.AdapterMode#resolve}'s
 * wg/nft rule (unset → {@code mock} outside a container): {@code ping}/{@code tracepath}/
 * {@code mtr} need no elevation and mutate nothing (ADR-0025 §3), so there is no safety
 * reason to hide them behind an explicit opt-in the way genuinely-privileged {@code wg}/
 * {@code nft} are. Same posture as Device Discovery (ADR-0014), which defaults to {@code real}
 * outside a container for exactly this reason. An explicit value always wins; unset defaults
 * to {@code socket} inside a container (main image likely lacks the tools; the proxy's host
 * install guarantees them) and {@code real} on a bare host. Dev/test set an explicit
 * {@code mock} in {@code application.properties} so a laptop or CI run never shells out.
 *
 * <p>A separate property from {@code islandr.wg.mode} rather than reusing it — a deployment
 * could plausibly want real {@code wg} but no diagnostics tooling (or vice versa) — but the
 * same socket path and container detection are reused, since it is the same host proxy either way.
 */
@ApplicationScoped
public class NetworkDiagnosticsAdapterProducer {

    private static final Logger LOG = Logger.getLogger(NetworkDiagnosticsAdapterProducer.class);

    @ConfigProperty(name = "islandr.diag.mode")
    Optional<String> mode;

    @ConfigProperty(name = "islandr.proxy.socket", defaultValue = "/run/islandr/proxy.sock")
    String proxySocket;

    @ConfigProperty(name = "islandr.proxy.timeout", defaultValue = "2s")
    Duration proxyTimeout;

    @Inject ContainerDetector containerDetector;

    @Produces
    @ApplicationScoped
    public NetworkDiagnosticsAdapter produce() {
        String resolved = resolveMode();
        switch (resolved) {
            case "real":
                LOG.info("NetworkDiagnosticsAdapter mode=real — ping/tracepath run unprivileged (ADR-0025 §3)");
                return new RealNetworkDiagnosticsAdapter();
            case "socket":
                LOG.infof("NetworkDiagnosticsAdapter mode=socket — talking to host proxy at %s (timeout %s)", proxySocket, proxyTimeout);
                return new SocketNetworkDiagnosticsAdapter(new ProxyClient(Path.of(proxySocket), proxyTimeout));
            default:
                LOG.info("NetworkDiagnosticsAdapter mode=mock — using in-memory implementation");
                return new MockNetworkDiagnosticsAdapter();
        }
    }

    /** See the class doc comment — unset resolves to {@code real} outside a container, not {@code mock}. */
    private String resolveMode() {
        if (mode.isPresent() && !mode.get().isBlank()) return mode.get().trim().toLowerCase();
        return containerDetector.inContainer() ? "socket" : "real";
    }
}
