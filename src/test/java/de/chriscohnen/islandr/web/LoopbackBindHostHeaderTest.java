package de.chriscohnen.islandr.web;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Islandr bound to the loopback address behind a reverse proxy — the documented
 * setup (docs/install/reverse-proxy.md) — must answer for the proxy's host name.
 *
 * <p>Quarkus 3.40 turns on host validation by itself when the server listens on
 * localhost and then answers 400 to every request whose Host header is not a
 * localhost name. A reverse proxy forwards the public name, so every request
 * through it was refused (found on the lab hub with rc.2, behind Traefik, and
 * reproduced with the built jar bound to 127.0.0.1).
 *
 * <p>The test harness cannot show it — a {@code @QuarkusTest} does not start the
 * server the way a production launch does, and answers 200 either way — so this
 * pins the setting that prevents it instead of the symptom.
 */
@QuarkusTest
class LoopbackBindHostHeaderTest {

    @Test
    void hostValidationIsNotLeftToQuarkusAutomaticLoopbackRule() {
        var value = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.host-validation.require-localhost", Boolean.class);
        assertThat(value).contains(false);
    }
}
