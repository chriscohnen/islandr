package de.chriscohnen.islandr.acl;

import java.util.List;

public class AtlasDto {

    public record UserNode(String id, String name) {}

    public record ResourceNode(
            String id, String name, String type, String siteId, String siteName,
            String siteCidr, String ip, String description, String siteGatewayPeerId) {}

    /** kind: "role" | "type-grant" | "user-direct" | "site-direct". roleId/roleName are null
     * except for "role"/"type-grant". subjectType: "user" (subjectId is a User.id, the only
     * kind before site-direct existed) or "site" (subjectId is a Site.id — site-direct only). */
    public record Edge(
            String subjectType, String subjectId, String resourceId, String kind,
            String roleId, String roleName,
            boolean allPorts, List<String> portLabels) {}

    public record RoleOption(String id, String name, List<String> memberUserIds) {}

    /** Every Site, independent of whether it owns any resources — a site that only ever
     * grants (never receives) access has no ResourceNode of its own to hang a name/cidr
     * off of, so the frontend needs this list to draw its gateway node regardless. */
    public record SiteNode(String id, String name, String cidr, String gatewayPeerId) {}

    public record Graph(
            List<UserNode> users, List<ResourceNode> resources,
            List<Edge> edges, List<RoleOption> roles, List<SiteNode> sites) {}
}
