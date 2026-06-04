package de.chriscohnen.islandr.firewall;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Picks the {@link NftablesAdapter} implementation at startup. Mirrors
 * {@code WgAdapterProducer}: default {@code mock}, Hub VM sets
 * {@code islandr.nft.mode=real}.
 */
@ApplicationScoped
public class NftablesAdapterProducer {

    private static final Logger LOG = Logger.getLogger(NftablesAdapterProducer.class);

    @ConfigProperty(name = "islandr.nft.mode", defaultValue = "mock")
    String mode;

    @ConfigProperty(name = "islandr.use-sudo", defaultValue = "false")
    boolean useSudo;

    @Produces
    @ApplicationScoped
    public NftablesAdapter produce() {
        if ("real".equalsIgnoreCase(mode)) {
            LOG.infof("NftablesAdapter mode=real, useSudo=%s — shelling out to `nft` CLI", useSudo);
            return new RealNftablesAdapter(useSudo);
        }
        LOG.info("NftablesAdapter mode=mock — in-memory, no real nftables interaction");
        return new MockNftablesAdapter();
    }
}
