package de.chriscohnen.islandr.network;

import de.chriscohnen.islandr.proxy.ContainerDetector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the mode-resolution rule ADR-0025 deliberately diverges from
 * {@code AdapterMode.resolve} on: unset {@code islandr.diag.mode} resolves to
 * {@code real} outside a container (Device Discovery's posture, ADR-0014), not
 * {@code mock} the way wg/nft's genuinely-privileged ops do. An explicit value
 * still always wins either way.
 */
class NetworkDiagnosticsAdapterProducerTest {

    private static ContainerDetector detector(boolean inContainer) throws Exception {
        Path marker = Files.createTempFile("container-marker-", "");
        if (!inContainer) Files.delete(marker);
        Constructor<ContainerDetector> ctor = ContainerDetector.class.getDeclaredConstructor(List.class);
        ctor.setAccessible(true);
        return ctor.newInstance((Object) List.of(marker));
    }

    private static NetworkDiagnosticsAdapterProducer producer(Optional<String> mode, boolean inContainer) throws Exception {
        NetworkDiagnosticsAdapterProducer p = new NetworkDiagnosticsAdapterProducer();
        setField(p, "mode", mode);
        setField(p, "proxySocket", "/run/islandr/proxy.sock");
        setField(p, "proxyTimeout", Duration.ofSeconds(2));
        setField(p, "containerDetector", detector(inContainer));
        return p;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = NetworkDiagnosticsAdapterProducer.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void unsetModeOutsideContainer_resolvesToReal_notMock() throws Exception {
        NetworkDiagnosticsAdapterProducer p = producer(Optional.empty(), false);
        assertThat(p.produce()).isInstanceOf(RealNetworkDiagnosticsAdapter.class);
    }

    @Test
    void unsetModeInContainer_resolvesToSocket() throws Exception {
        NetworkDiagnosticsAdapterProducer p = producer(Optional.empty(), true);
        assertThat(p.produce()).isInstanceOf(SocketNetworkDiagnosticsAdapter.class);
    }

    @Test
    void explicitMockWins_evenOutsideContainer() throws Exception {
        NetworkDiagnosticsAdapterProducer p = producer(Optional.of("mock"), false);
        assertThat(p.produce()).isInstanceOf(MockNetworkDiagnosticsAdapter.class);
    }

    @Test
    void explicitSocketWins_evenOutsideContainer() throws Exception {
        NetworkDiagnosticsAdapterProducer p = producer(Optional.of("socket"), false);
        assertThat(p.produce()).isInstanceOf(SocketNetworkDiagnosticsAdapter.class);
    }
}
