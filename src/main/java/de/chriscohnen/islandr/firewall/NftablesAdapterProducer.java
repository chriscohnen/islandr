package de.chriscohnen.islandr.firewall;

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
 * Picks the {@link NftablesAdapter} implementation at startup. Mirrors
 * {@code WgAdapterProducer} and shares the same mode-resolution rule
 * ({@link AdapterMode}): explicit {@code islandr.nft.mode} wins, else a container
 * defaults to {@code socket} and a bare host to {@code mock}. The Hub VM sets
 * {@code islandr.nft.mode=real}.
 */
@ApplicationScoped
public class NftablesAdapterProducer {

    private static final Logger LOG = Logger.getLogger(NftablesAdapterProducer.class);

    @ConfigProperty(name = "islandr.nft.mode")
    Optional<String> mode;

    @ConfigProperty(name = "islandr.use-sudo", defaultValue = "false")
    boolean useSudo;

    @ConfigProperty(name = "islandr.data.dir", defaultValue = "/var/lib/islandr")
    String dataDir;

    @ConfigProperty(name = "islandr.proxy.socket", defaultValue = "/run/islandr/proxy.sock")
    String proxySocket;

    @ConfigProperty(name = "islandr.proxy.ruleset-path", defaultValue = "/var/lib/islandr/ruleset.nft")
    String rulesetPath;

    @ConfigProperty(name = "islandr.proxy.timeout", defaultValue = "2s")
    Duration proxyTimeout;

    @Inject SettingsService settings;

    @Inject ContainerDetector containerDetector;

    @Produces
    @ApplicationScoped
    public NftablesAdapter produce() {
        String resolved = AdapterMode.resolve(mode, containerDetector.inContainer());
        switch (resolved) {
            case "real":
                LOG.infof("NftablesAdapter mode=real, useSudo=%s — wrapped with DryRunNftablesAdapter (checks settings at runtime)", useSudo);
                return new DryRunNftablesAdapter(new RealNftablesAdapter(useSudo, Path.of(dataDir)), settings);
            case "socket":
                LOG.infof("NftablesAdapter mode=socket — staging to %s, reload via host proxy at %s", rulesetPath, proxySocket);
                return new SocketNftablesAdapter(new ProxyClient(Path.of(proxySocket), proxyTimeout), Path.of(rulesetPath));
            default:
                LOG.info("NftablesAdapter mode=mock — in-memory, no real nftables interaction");
                return new MockNftablesAdapter();
        }
    }
}
