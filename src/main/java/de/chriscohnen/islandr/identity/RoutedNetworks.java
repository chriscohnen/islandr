package de.chriscohnen.islandr.identity;

import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * The networks the hub routes into: its WireGuard subnets, every site
 * network, and what site-to-site peers announce. Reachable from the hub and
 * from nowhere else — so a URL resolving into one of them must not be fetched
 * on a user's say-so, even when the range is public ({@link UrlFetchPolicy}).
 */
@ApplicationScoped
public class RoutedNetworks {

    @Inject SettingsService settings;

    public List<String> cidrs() {
        List<String> out = new ArrayList<>();
        Settings s = settings.get();
        if (s.wgSubnet != null) out.add(s.wgSubnet);
        if (s.wgSubnet6 != null) out.add(s.wgSubnet6);
        for (Site site : Site.<Site>listAll()) {
            if (site.cidr != null) out.add(site.cidr);
        }
        for (Peer p : Peer.<Peer>list("siteAllowedCidrs is not null")) {
            for (String c : p.siteAllowedCidrs.split(",")) out.add(c.trim());
        }
        return out;
    }
}
