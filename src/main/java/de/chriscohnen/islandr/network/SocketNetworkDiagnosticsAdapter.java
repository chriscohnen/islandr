package de.chriscohnen.islandr.network;

import com.fasterxml.jackson.databind.JsonNode;
import de.chriscohnen.islandr.proxy.ProxyClient;
import de.chriscohnen.islandr.proxy.ProxyResponse;
import de.chriscohnen.islandr.proxy.ProxyUnavailableException;

import java.util.Map;

/**
 * {@link NetworkDiagnosticsAdapter} for the {@code islandr.diag.mode=socket} runtime: an
 * unprivileged container talking to the host-side {@code islandr-proxy} over a Unix socket
 * (ADR-0012, ADR-0025 §3). The proxy's own host install guarantees {@code iputils}, so this
 * adapter is where the "is it installed" question gets an answer the main container's own
 * (deliberately minimal) image cannot give.
 *
 * <p>Parsing is delegated to {@link RealNetworkDiagnosticsAdapter}'s static parse methods —
 * the proxy hands back raw {@code ping}/{@code tracepath} stdout, same report shape either
 * side of the socket, one parser to keep correct.
 */
public class SocketNetworkDiagnosticsAdapter implements NetworkDiagnosticsAdapter {

    private final ProxyClient client;

    public SocketNetworkDiagnosticsAdapter(ProxyClient client) {
        this.client = client;
    }

    @Override
    public Availability checkAvailability() {
        try {
            ProxyResponse response = client.send(Map.of("op", "net_availability"));
            if (!response.ok()) return new Availability(false, false, false);
            JsonNode body = response.body();
            return new Availability(
                    body.path("ping").asBoolean(false),
                    body.path("tracepath").asBoolean(false),
                    body.path("mtr").asBoolean(false));
        } catch (ProxyUnavailableException e) {
            return new Availability(false, false, false);
        }
    }

    @Override
    public PingResult ping(String ip, int count) {
        ProxyResponse response = client.send(Map.of("op", "net_ping", "ip", ip, "count", count));
        if (!response.ok()) {
            throw new NetworkDiagnosticsException("net_ping failed: " + response.error());
        }
        String dump = response.body().path("dump").asText("");
        return RealNetworkDiagnosticsAdapter.parsePingOutput(dump, count);
    }

    @Override
    public TracepathResult tracepath(String ip) {
        ProxyResponse response = client.send(Map.of("op", "net_tracepath", "ip", ip));
        if (!response.ok()) {
            throw new NetworkDiagnosticsException("net_tracepath failed: " + response.error());
        }
        String dump = response.body().path("dump").asText("");
        return RealNetworkDiagnosticsAdapter.parseTracepathOutput(dump);
    }
}
