package de.chriscohnen.islandr.acl;

import java.util.List;

public class AtlasDto {

    public record UserNode(String id, String name) {}

    public record ResourceNode(
            String id, String name, String type, String siteId, String siteName,
            String siteCidr, String ip, String description, String siteGatewayPeerId,
            String siteGatewayPeerName) {}

    /** kind: "role" | "type-grant" | "user-direct" | "site-direct" | "network-grant".
     * roleId/roleName are null except for "role"/"type-grant"/"network-grant".
     * subjectType: "user" (subjectId is a User.id) or "site" (subjectId is a
     * Site.id — site-direct only). resourceId is null for "network-grant"
     * (there is no single resource — the target is the whole network) and
     * non-null for every other kind; siteId is the mirror image: non-null
     * only for "network-grant" (the granted Site.id), null otherwise. */
    public record Edge(
            String subjectType, String subjectId, String resourceId, String siteId, String kind,
            String roleId, String roleName,
            boolean allPorts, List<String> portLabels, List<PortDetail> portDetails) {}

    /**
     * One port a limited grant is scoped to, in the form a machine can act on.
     *
     * <p>{@code portLabels} above is the console's rendering — {@code "SSH 22"},
     * built from the UI's application label and the number. It cannot be parsed
     * back: {@code transport} (tcp/udp/both) never appears in it, and that is
     * the one field a firewall rule cannot be written without. This record
     * carries the values themselves, so a consumer of the external API never
     * has to take a display string apart.
     *
     * @param protocol the UI's application label (RDP / SSH / HTTP / CUSTOM),
     *                 kept because it is what a human recognises — it is not
     *                 the transport and must not be read as one.
     * @param label    the free-text name an admin gave this port, or null.
     *                 Not the rendered string in {@code portLabels} above:
     *                 that one is built from protocol and number, this one is
     *                 what a person typed ("RDP for IT" next to "RDP for VPN").
     */
    public record PortDetail(
            String id, int port, Integer portEnd, String transport,
            String protocol, String label) {}

    public record RoleOption(String id, String name, List<String> memberUserIds) {}

    /** Every Site, independent of whether it owns any resources — a site that only ever
     * grants (never receives) access has no ResourceNode of its own to hang a name/cidr
     * off of, so the frontend needs this list to draw its gateway node regardless. */
    public record SiteNode(String id, String name, String cidr, String gatewayPeerId, String gatewayPeerName) {}

    public record Graph(
            List<UserNode> users, List<ResourceNode> resources,
            List<Edge> edges, List<RoleOption> roles, List<SiteNode> sites,
            // The Hub's own tunnel address(es) — network+1 of wgSubnet/wgSubnet6
            // (Settings.effectiveClientDns' "server address" convention), so the
            // Atlas diagram's hover on the Hub node can show something concrete
            // instead of just a label. hubIp6 is null on IPv4-only installs or if
            // wgSubnet6 fails to parse.
            String hubIp4, String hubIp6) {}
}
