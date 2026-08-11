package de.chriscohnen.islandr.acl;

import java.util.List;

public class AtlasDto {

    public record PeerNode(String id, String name, String type) {}

    /** ownership: "grant" | "type-grant" | null (null only when reachable=false). */
    public record ResourceNode(
            String id, String name, String type, String siteId, String siteName,
            boolean reachable, String ownership) {}

    public record Edge(
            String peerId, String resourceId, String roleId, String roleName,
            boolean allPorts, List<String> portLabels) {}

    public record RoleOption(String id, String name) {}

    public record Graph(
            List<PeerNode> peers, List<ResourceNode> resources,
            List<Edge> edges, List<RoleOption> roles) {}
}
