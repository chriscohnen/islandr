package de.chriscohnen.islandr.proxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config contract for ADR-0012.
 *
 * <p>{@link AdapterModeTest} proves the resolution rule is correct in isolation:
 * an unset mode falls back to {@code socket} inside a container. That fallback is
 * only reachable while the mode really is unset. An adapter mode written to
 * application.properties <em>without</em> a profile prefix applies to every profile,
 * counts as "explicit" in {@link AdapterMode#resolve}, and silently wins over the
 * container default — the published image would then run the mock adapter, which
 * fakes success and even simulates online peers, hiding from the operator that
 * nothing is enforced on the host kernel (ADR-0012, risk R-122).
 *
 * <p>The unit test cannot catch that: the rule stays correct while the wiring
 * defeats it. This test therefore asserts the wiring itself.
 */
class AdapterModeDefaultsConfigTest {

    private static List<String> propertyLines() throws IOException {
        try (InputStream in = AdapterModeDefaultsConfigTest.class.getResourceAsStream("/application.properties")) {
            assertThat(in).as("application.properties must be on the test classpath").isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream(content.split("\\R")).map(String::strip).toList();
        }
    }

    @Test
    void adapterModesAreNeverSetUnconditionally_soAContainerCanDefaultToSocket() throws IOException {
        List<String> unconditional = propertyLines().stream()
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.startsWith("islandr.wg.mode=") || line.startsWith("islandr.nft.mode="))
                .toList();

        assertThat(unconditional)
                .as("An adapter mode without a profile prefix wins over the container default in "
                        + "AdapterMode.resolve(), so the image would run the success-faking mock instead of "
                        + "the degraded socket adapter (ADR-0012, R-122). Scope these to %%dev / %%test.")
                .isEmpty();
    }

    @Test
    void devAndTestStillPinTheMockAdapter() throws IOException {
        assertThat(propertyLines()).contains(
                "%dev.islandr.wg.mode=mock",
                "%test.islandr.wg.mode=mock",
                "%dev.islandr.nft.mode=mock",
                "%test.islandr.nft.mode=mock");
    }
}
