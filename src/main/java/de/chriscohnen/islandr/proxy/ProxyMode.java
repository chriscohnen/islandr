package de.chriscohnen.islandr.proxy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Answers whether enforcement runs through the host socket proxy, applying the
 * same {@link AdapterMode} resolution the producers use. Used to gate the
 * reconciler (only socket mode probes the proxy) and as the diagnostic on the
 * enforcement-status endpoint. In {@code real}/{@code mock} mode this is
 * {@code false}, so degraded-mode machinery stays dormant.
 */
@ApplicationScoped
public class ProxyMode {

    @ConfigProperty(name = "islandr.wg.mode")
    Optional<String> wgMode;

    @ConfigProperty(name = "islandr.nft.mode")
    Optional<String> nftMode;

    @Inject ContainerDetector containerDetector;

    /** True when either adapter resolves to {@code socket} — i.e. enforcement goes via the proxy. */
    public boolean isSocket() {
        boolean inContainer = containerDetector.inContainer();
        return "socket".equals(AdapterMode.resolve(wgMode, inContainer))
                || "socket".equals(AdapterMode.resolve(nftMode, inContainer));
    }
}
