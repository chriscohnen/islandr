package de.chriscohnen.islandr.network;

import java.util.List;

public class NetworkDiagnosticsDto {

    public record AvailabilityView(boolean ping, boolean tracepath, boolean mtr) {}

    /** One node of the probed path, in hub → site-gateway-peer → resource order (ADR-0025 §5). */
    public record PathHop(String kind, String id, String name, String detail) {}

    // targetId/targetName, not resourceId/resourceName: the probe target is either a
    // Resource (ResourceResource) or a site's gateway Peer itself (PeerResource) —
    // one shared response shape for both (NetworkDiagnosticsService).
    public record PingResponse(
            String targetId, String targetName, String targetIp,
            boolean reachable, int sent, int received, double lossPercent,
            Double minMs, Double avgMs, Double maxMs, Double mdevMs,
            List<PathHop> path) {}

    public record TracepathHopView(int ttl, String host, Double ms) {}

    public record TracepathResponse(
            String targetId, String targetName, String targetIp,
            List<TracepathHopView> hops, List<PathHop> path) {}

    public record MtrHopView(int ttl, String host, double lossPercent, int sent,
                              Double lastMs, Double avgMs, Double bestMs, Double worstMs) {}

    public record MtrResponse(
            String targetId, String targetName, String targetIp,
            List<MtrHopView> hops, List<PathHop> path) {}
}
