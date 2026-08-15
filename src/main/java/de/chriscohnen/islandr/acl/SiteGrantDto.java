package de.chriscohnen.islandr.acl;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class SiteGrantDto {
    public record Update(
            @NotBlank String siteId,
            @NotBlank String resourceId,
            boolean allPorts,
            List<String> portIds   // ignored when allPorts=true
    ) {}

    /** One row for the ACL page's direct-grants list (GET /api/v1/acl/site-grants).
     * grantorSiteName is the granting site; resourceSiteName is the resource's own
     * site — a cross-site grant (site A -> resource in site B) is legal, so both
     * names matter and must not be conflated into a single "siteName" field. */
    public record ListItem(
            String siteId, String grantorSiteName,
            String resourceId, String resourceName, String resourceSiteName,
            boolean allPorts, List<String> portLabels) {}
}
