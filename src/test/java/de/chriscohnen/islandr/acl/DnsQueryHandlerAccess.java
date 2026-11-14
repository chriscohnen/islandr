package de.chriscohnen.islandr.acl;

/** Test helper: the slug a site's resources resolve under (site name, lowercased). */
final class DnsQueryHandlerAccess {
    private DnsQueryHandlerAccess() {}

    static String slug(Site site) {
        return site.name.toLowerCase(java.util.Locale.ROOT);
    }
}
