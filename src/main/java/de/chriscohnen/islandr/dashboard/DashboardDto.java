package de.chriscohnen.islandr.dashboard;

import java.time.Instant;
import java.util.List;

public final class DashboardDto {

    public record Response(
            PeerStats peers,
            UserStats users,
            RoleStats roles,
            ResourceStats resources,
            SetupStatus setup,
            FirewallStatus firewall,
            List<AuditEntry> recentAudit,
            List<PeerEntry> recentPeers,
            Topology topology
    ) {}

    /**
     * Compact firewall snapshot for the dashboard card. {@code status} is
     * {@code ok} / {@code failed} / {@code never}. {@code stderr} is only
     * set on {@code failed} so the UI can surface the validation message.
     */
    public record FirewallStatus(
            String status,
            int ruleCount,
            java.time.Instant lastOkAt,
            String stderr
    ) {}

    /**
     * Topology widget data. Two stable rings around the hub:
     *   1. {@code sites} — inner ring, one node per site
     *   2. {@code resources} — outer ring, grouped under their parent site
     * Plus {@code livePeers} — tiny dots near the hub representing peers
     * that exchanged a handshake in the last 5 minutes. These are the only
     * thing that comes and goes; everything else is static topology.
     *
     * Resources beyond {@link DashboardResource#TOPOLOGY_RESOURCE_CAP}
     * collapse into {@code resourceOverflow} so the SVG stays legible.
     */
    public record Topology(
            List<TopologySite> sites,
            List<TopologyResource> resources,
            List<TopologyLivePeer> livePeers,
            int resourceOverflow,
            // Public WireGuard endpoint of the hub from Settings — rendered
            // under the central hub node so the operator sees at a glance
            // which DNS/IP the clients reach. Null/blank means Settings
            // hasn't been set up yet.
            String hubEndpoint,
            // Operator-set hub location name (Settings) shown as the hub node
            // label instead of the generic "Hub". Null/blank → "Hub".
            String hubLabel
    ) {}

    public record TopologySite(
            String id,
            String name,
            String cidr,
            int resourceCount,
            String gatewayPeerId,
            String gatewayPeerName,
            Boolean gatewayOnline,      // null = no gateway configured
            String gatewayIp,
            Instant gatewayLastSeenAt
    ) {}

    public record TopologyResource(
            String id,
            String siteId,
            String name,
            String ip,
            String type,
            List<String> portLabels,
            int portCount
    ) {}

    public record TopologyLivePeer(
            String id,
            String name,
            // 'client' | 'site' — peers of type=site are static gateways and
            // already represented by a TopologySite node; we still render them
            // as live dots when their handshake is fresh so the operator sees
            // the tunnel is alive.
            String type,
            String assignedIp,
            Instant lastSeenAt
    ) {}

    public record PeerStats(
            long total,
            long enabled,
            // Peers that exchanged a WireGuard handshake at least once in the last 24h.
            // 0 until the activity-poller is wired up — left in the contract so the
            // frontend doesn't have to change once it lands.
            long lastSeen24h
    ) {}

    public record UserStats(long total, long admins) {}

    public record RoleStats(long total, long withGrants) {}

    public record ResourceStats(long sites, long resources, long ports) {}

    public record SetupStatus(
            boolean wgConfigured,
            // 'microsoft' | 'google' | null. Null means no provider active —
            // org users can't log in via OIDC, only the local admin works.
            String oidcProvider,
            String privateKeyRetention,
            boolean gravatarEnabled,
            boolean firewallDryRun
    ) {}

    public record AuditEntry(
            String id,
            String actor,
            String action,
            String target,
            Instant createdAt
    ) {}

    public record PeerEntry(
            String id,
            String name,
            String userId,
            String userName,
            String assignedIp,
            boolean enabled,
            Instant lastSeenAt
    ) {}

    private DashboardDto() {}
}
