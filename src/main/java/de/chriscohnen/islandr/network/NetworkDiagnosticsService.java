package de.chriscohnen.islandr.network;

import de.chriscohnen.islandr.audit.AuditService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared ping/tracepath probe logic (ADR-0025): cooldown, audit logging, and DTO assembly,
 * used by both {@code ResourceResource} (probing a Resource) and {@code PeerResource} (probing
 * a site's gateway peer directly — testing the tunnel itself, not what's behind it). Factored
 * out rather than duplicated once a second probe target (the peer) needed the exact same
 * cooldown/audit/error shape as the first.
 */
@ApplicationScoped
public class NetworkDiagnosticsService {

    /** Fixed, not admin-tunable — ADR-0025 R-183: bounded sample count regardless of caller input. */
    private static final int PING_COUNT = 4;

    /** Same rationale as {@link #PING_COUNT} — mtr's cycles-per-hop, fixed server-side. */
    private static final int MTR_CYCLES = 4;

    /** Minimum spacing between two probes against the *same* target — ADR-0025 R-183/T-018. */
    private static final Duration COOLDOWN = Duration.ofSeconds(3);

    // Keyed by a caller-chosen cooldown key (e.g. "resource:<id>" / "peer:<id>") so a
    // resource and a peer that happen to share a UUID prefix can never collide.
    private static final Map<String, Instant> lastProbeAt = new ConcurrentHashMap<>();

    @Inject NetworkDiagnosticsAdapter diagnostics;
    @Inject AuditService audit;

    public NetworkDiagnosticsDto.PingResponse ping(String cooldownKey, String actor, String auditTargetLabel,
                                                    String targetId, String targetName, String targetIp,
                                                    List<NetworkDiagnosticsDto.PathHop> path) {
        requireCooldownElapsed(cooldownKey);
        try {
            NetworkDiagnosticsAdapter.PingResult r = diagnostics.ping(targetIp, PING_COUNT);
            audit.logEvent(actor, "diagnostics.ping", auditTargetLabel,
                    Map.of("ip", targetIp, "reachable", r.reachable(), "lossPercent", r.lossPercent(),
                            "avgMs", r.avgMs() != null ? r.avgMs() : -1));
            return new NetworkDiagnosticsDto.PingResponse(targetId, targetName, targetIp,
                    r.reachable(), r.sent(), r.received(), r.lossPercent(),
                    r.minMs(), r.avgMs(), r.maxMs(), r.mdevMs(), path);
        } catch (NetworkDiagnosticsException e) {
            audit.logEvent(actor, "diagnostics.ping_failed", auditTargetLabel, Map.of("ip", targetIp, "error", e.getMessage()));
            throw serviceUnavailable(e.getMessage());
        }
    }

    public NetworkDiagnosticsDto.TracepathResponse tracepath(String cooldownKey, String actor, String auditTargetLabel,
                                                              String targetId, String targetName, String targetIp,
                                                              List<NetworkDiagnosticsDto.PathHop> path) {
        requireCooldownElapsed(cooldownKey);
        try {
            NetworkDiagnosticsAdapter.TracepathResult r = diagnostics.tracepath(targetIp);
            audit.logEvent(actor, "diagnostics.tracepath", auditTargetLabel, Map.of("ip", targetIp, "hops", r.hops().size()));
            List<NetworkDiagnosticsDto.TracepathHopView> hops = new ArrayList<>();
            for (NetworkDiagnosticsAdapter.TracepathHop h : r.hops()) {
                hops.add(new NetworkDiagnosticsDto.TracepathHopView(h.ttl(), h.host(), h.ms()));
            }
            return new NetworkDiagnosticsDto.TracepathResponse(targetId, targetName, targetIp, hops, path);
        } catch (NetworkDiagnosticsException e) {
            audit.logEvent(actor, "diagnostics.tracepath_failed", auditTargetLabel, Map.of("ip", targetIp, "error", e.getMessage()));
            throw serviceUnavailable(e.getMessage());
        }
    }

    /** Opportunistic upgrade over {@link #tracepath} — same cooldown key, so the two never
     *  combine into a higher effective probe rate against one target (ADR-0025 R-183/T-018). */
    public NetworkDiagnosticsDto.MtrResponse mtr(String cooldownKey, String actor, String auditTargetLabel,
                                                  String targetId, String targetName, String targetIp,
                                                  List<NetworkDiagnosticsDto.PathHop> path) {
        requireCooldownElapsed(cooldownKey);
        try {
            NetworkDiagnosticsAdapter.MtrResult r = diagnostics.mtr(targetIp, MTR_CYCLES);
            audit.logEvent(actor, "diagnostics.mtr", auditTargetLabel, Map.of("ip", targetIp, "hops", r.hops().size()));
            List<NetworkDiagnosticsDto.MtrHopView> hops = new ArrayList<>();
            for (NetworkDiagnosticsAdapter.MtrHop h : r.hops()) {
                hops.add(new NetworkDiagnosticsDto.MtrHopView(h.ttl(), h.host(), h.lossPercent(), h.sent(),
                        h.lastMs(), h.avgMs(), h.bestMs(), h.worstMs()));
            }
            return new NetworkDiagnosticsDto.MtrResponse(targetId, targetName, targetIp, hops, path);
        } catch (NetworkDiagnosticsException e) {
            audit.logEvent(actor, "diagnostics.mtr_failed", auditTargetLabel, Map.of("ip", targetIp, "error", e.getMessage()));
            throw serviceUnavailable(e.getMessage());
        }
    }

    /** ADR-0025 R-183/T-018: refuses a probe fired again against the same target inside the cooldown. */
    private void requireCooldownElapsed(String cooldownKey) {
        Instant now = Instant.now();
        Instant previous = lastProbeAt.put(cooldownKey, now);
        if (previous != null && Duration.between(previous, now).compareTo(COOLDOWN) < 0) {
            lastProbeAt.put(cooldownKey, previous); // don't let a rejected call reset the window
            throw new WebApplicationException(Response.status(429)
                    .entity("probe already running for this target — wait a moment and retry").build());
        }
    }

    private WebApplicationException serviceUnavailable(String message) {
        return new WebApplicationException(Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(message).build());
    }
}
