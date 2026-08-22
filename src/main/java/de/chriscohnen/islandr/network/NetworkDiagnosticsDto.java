package de.chriscohnen.islandr.network;

import java.util.List;

public class NetworkDiagnosticsDto {

    public record AvailabilityView(boolean ping, boolean tracepath, boolean mtr) {}

    /** One node of the probed path, in hub → site-gateway-peer → resource order (ADR-0025 §5). */
    public record PathHop(String kind, String id, String name, String detail) {}

    public record PingResponse(
            String resourceId, String resourceName, String targetIp,
            boolean reachable, int sent, int received, double lossPercent,
            Double minMs, Double avgMs, Double maxMs, Double mdevMs,
            List<PathHop> path) {}

    public record TracepathHopView(int ttl, String host, Double ms) {}

    public record TracepathResponse(
            String resourceId, String resourceName, String targetIp,
            List<TracepathHopView> hops, List<PathHop> path) {}
}
