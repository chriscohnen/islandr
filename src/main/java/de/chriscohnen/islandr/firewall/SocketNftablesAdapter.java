package de.chriscohnen.islandr.firewall;

import de.chriscohnen.islandr.proxy.ProxyClient;
import de.chriscohnen.islandr.proxy.ProxyResponse;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@link NftablesAdapter} for the {@code islandr.nft.mode=socket} runtime: the
 * unprivileged container stages the candidate ruleset to a file shared with the
 * host, then asks the host-side {@code islandr-proxy} to validate or reload it
 * (ADR-0012, design §3/§4).
 *
 * <p>The ruleset path is a <strong>server constant</strong> on the proxy side, so
 * it is never sent in the request — the container and proxy agree on the shared
 * file out of band (a mounted volume). The {@code flush table inet islandr} safety
 * invariant still lives in the rule generator; this adapter only stages and
 * triggers, exactly like {@link RealNftablesAdapter}.
 *
 * <p>A reachable proxy reporting {@code ok:false} is an operational failure
 * ({@code validate} → {@link ValidationResult#fail}, {@code apply} →
 * {@link NftablesException}). An unreachable proxy raises
 * {@link de.chriscohnen.islandr.proxy.ProxyUnavailableException}, which is allowed
 * to propagate so the call-site enters the degraded "enforcement unavailable" state.
 */
public class SocketNftablesAdapter implements NftablesAdapter {

    private static final Logger LOG = Logger.getLogger(SocketNftablesAdapter.class);

    private final ProxyClient client;
    private final Path rulesetPath;

    public SocketNftablesAdapter(ProxyClient client, Path rulesetPath) {
        this.client = client;
        this.rulesetPath = rulesetPath;
    }

    @Override
    public ValidationResult validate(String rulesetText) {
        try {
            stage(rulesetText);
        } catch (IOException e) {
            return ValidationResult.fail("could not stage ruleset: " + e.getMessage());
        }
        ProxyResponse response = client.send(Map.of("op", "nft_validate"));
        if (response.ok()) {
            return ValidationResult.success();
        }
        LOG.warnf("proxy nft_validate rejected ruleset: %s", response.error());
        return ValidationResult.fail(response.error());
    }

    @Override
    public void apply(String rulesetText) {
        try {
            stage(rulesetText);
        } catch (IOException e) {
            throw new NftablesException("could not stage ruleset for reload: " + e.getMessage(), e);
        }
        ProxyResponse response = client.send(Map.of("op", "nft_reload"));
        if (!response.ok()) {
            throw new NftablesException("proxy nft_reload failed: " + response.error());
        }
        LOG.infof("nftables reloaded via proxy (%d bytes staged to %s)", rulesetText.length(), rulesetPath);
    }

    /** Write the candidate ruleset to the shared file the proxy reads from. */
    private void stage(String rulesetText) throws IOException {
        Files.writeString(rulesetPath, rulesetText, StandardCharsets.UTF_8);
    }
}
