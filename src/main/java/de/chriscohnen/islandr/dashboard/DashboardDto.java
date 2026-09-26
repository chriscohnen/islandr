package de.chriscohnen.islandr.dashboard;

import de.chriscohnen.islandr.hosthealth.HostHealthDto;

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
            Topology topology,
            // Hub CPU/memory/swap (#73) — a live sample, not a DB-backed stat
            // like everything else above. "unavailable" status on a non-Linux
            // dev machine (no /proc), never null.
            HostHealthDto.Snapshot hostHealth
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
            String hubLabel,
            // Hub coordinates (Settings.hubLat/hubLon), manually entered —
            // null unless the operator has set them. Backs the world-map
            // topology view (ADR-0021); unrelated to the radial diagram above.
            // "hubLon" (not "hubLng") to match the naming already established
            // by Settings/SettingsDto for the hub specifically.
            Double hubLat,
            Double hubLon
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
            Instant gatewayLastSeenAt,
            // Gateway peer's manually-entered coordinates (Peer.lat/lng, only
            // ever set for type=site peers) — null if never geocoded. Backs
            // the world-map topology view (ADR-0021).
            Double gatewayLat,
            Double gatewayLng
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
            long lastSeen24h,
            // Peers on the live interface that Islandr does not manage. They come
            // from the <iface>.conf Islandr never writes, keep working, and reach
            // the hub itself — the ruleset only filters forwarded traffic. Until
            // now they were visible only to an admin who opened the import dialog.
            // -1 means the interface could not be read.
            long unmanagedOnInterface
    ) {}

    public record UserStats(long total, long admins) {}

    public record RoleStats(long total, long withGrants) {}

    public record ResourceStats(long sites, long resources, long ports) {}

    public record SetupStatus(
            boolean wgConfigured,
            // 'microsoft' | 'google' | null. Null means no provider active —
            // org users can't log in via OIDC. They can still log in locally
            // (F-01a) if an admin has set them a password; see
            // hasLocalPasswordUsers, which is what actually decides whether
            // that leaves anyone able to sign in besides the ENV admin.
            String oidcProvider,
            // True once at least one org user has a local password set
            // (F-01a) — a deliberately OIDC-less, locally-managed install is
            // not a setup gap once this is true.
            boolean hasLocalPasswordUsers,
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
            // Null for a peer without an owner — every site gateway, by design.
            // The UI renders a dash; the API does not invent a label.
            String userName,
            String assignedIp,
            boolean enabled,
            Instant lastSeenAt,
            // "CONNECTED" | "STALE" | "DISCONNECTED", same source as the Peers
            // table (PeerConnectionStatus). The strip renders the badge from
            // this; without it every row fell back to "disconnected" no matter
            // how recent the handshake was.
            String connectionStatus
    ) {}

    private DashboardDto() {}
}
