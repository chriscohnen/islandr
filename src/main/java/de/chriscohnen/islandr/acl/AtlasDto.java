package de.chriscohnen.islandr.acl;

import java.util.List;

public class AtlasDto {

    public record UserNode(String id, String name) {}

    public record ResourceNode(
            String id, String name, String type, String siteId, String siteName,
            String siteCidr, String ip, String description) {}

    /** kind: "role" | "type-grant" | "user-direct". roleId/roleName are null for "user-direct". */
    public record Edge(
            String userId, String resourceId, String kind,
            String roleId, String roleName,
            boolean allPorts, List<String> portLabels) {}

    public record RoleOption(String id, String name) {}

    public record Graph(
            List<UserNode> users, List<ResourceNode> resources,
            List<Edge> edges, List<RoleOption> roles) {}
}
