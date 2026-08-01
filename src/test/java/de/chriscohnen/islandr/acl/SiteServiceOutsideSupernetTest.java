package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.peer.Peer;
import de.chriscohnen.islandr.settings.Settings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-blocking warning surfaced on a site whose CIDR falls outside the admin's
 * declared split-tunnel supernet (issue #33) — save must still succeed
 * (SiteService.create/update never reject on this), the warning is advisory only.
 */
@QuarkusTest
class SiteServiceOutsideSupernetTest {

    @Inject SiteService siteSvc;

    @Test
    @Transactional
    void outsideSplitSupernet_flaggedWhenSiteCidrFallsOutsideConfiguredSupernet() {
        Settings settings = Settings.findById(Settings.SINGLETON_ID);
        settings.tunnelMode = "SPLIT";
        settings.allowedIpsMode = "AUTO";
        settings.splitSupernet = "10.0.0.0/8";

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Peer gw = Peer.createNew(null, "site-gw-" + suffix,
                "SITEPUBKEY" + suffix + "AAAAAAAAAAAAAAAAAAAAAAAAAAA=", "10.8.0.241");
        gw.type = "site";
        gw.persistAndFlush();

        Site inside = siteSvc.create(new SiteDto.UpsertRequest(
                "inside-" + suffix, "10.20.0.0/16", null, gw.id));
        Site outside = siteSvc.create(new SiteDto.UpsertRequest(
                "outside-" + suffix, "192.168.1.0/24", null, gw.id));

        assertThat(siteSvc.toResponse(inside, 0).outsideSplitSupernet()).isFalse();
        assertThat(siteSvc.toResponse(outside, 0).outsideSplitSupernet()).isTrue();
    }

    @Test
    @Transactional
    void outsideSplitSupernet_nullWhenModeNotSplitAuto() {
        Settings settings = Settings.findById(Settings.SINGLETON_ID);
        settings.tunnelMode = "FULL";
        settings.allowedIpsMode = "MANUAL";
        settings.splitSupernet = "10.0.0.0/8";

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Peer gw = Peer.createNew(null, "site-gw-" + suffix,
                "SITEPUBKEY" + suffix + "AAAAAAAAAAAAAAAAAAAAAAAAAAA=", "10.8.0.242");
        gw.type = "site";
        gw.persistAndFlush();

        Site site = siteSvc.create(new SiteDto.UpsertRequest(
                "site-" + suffix, "192.168.1.0/24", null, gw.id));

        assertThat(siteSvc.toResponse(site, 0).outsideSplitSupernet()).isNull();
    }

    @Test
    @Transactional
    void outsideSplitSupernet_nullWhenNoGatewayPeer() {
        Settings settings = Settings.findById(Settings.SINGLETON_ID);
        settings.tunnelMode = "SPLIT";
        settings.allowedIpsMode = "AUTO";
        settings.splitSupernet = "10.0.0.0/8";

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Site site = siteSvc.create(new SiteDto.UpsertRequest(
                "site-" + suffix, "192.168.1.0/24", null, null));

        assertThat(siteSvc.toResponse(site, 0).outsideSplitSupernet()).isNull();
    }
}
