package de.chriscohnen.islandr.wg;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.ClientProxy;
import io.quarkus.arc.InstanceHandle;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Empties the mock WireGuard interface before each test.
 *
 * <p>The mock adapter is a singleton for the whole Quarkus instance and outlives
 * the database rows individual test classes delete. Since peer creation rejects
 * an address held by a peer on the interface that Islandr does not manage
 * (an unimported peer on an adopted hub), a leftover mock entry from an earlier
 * class makes a fixed test address collide. Tests that pin addresses start from
 * an empty interface instead.
 */
public class CleanWgInterfaceExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        // Auto-detected, so this also fires while Quarkus is restarting for a
        // @TestProfile — Arc.container() is null in that window. Nothing to
        // clear then: the restart brings a fresh MockWgAdapter anyway.
        ArcContainer container = Arc.container();
        if (container == null || !container.isRunning()) {
            return;
        }
        InstanceHandle<WgAdapter> handle = container.instance(WgAdapter.class);
        if (!handle.isAvailable()) {
            return;
        }
        if (ClientProxy.unwrap(handle.get()) instanceof MockWgAdapter mock) {
            mock.reset();
        }
    }
}
