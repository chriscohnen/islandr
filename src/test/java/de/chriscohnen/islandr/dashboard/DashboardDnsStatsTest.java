package de.chriscohnen.islandr.dashboard;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.dns.DnsQueryStats;
import de.chriscohnen.islandr.settings.Settings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;

/**
 * dns-query-stats: the dashboard's DNS tile is null unless the resolver is
 * both enabled in Settings and actually bound, and otherwise reports the
 * resolver's own in-memory {@link DnsQueryStats} split, not a separately-
 * computed figure.
 *
 * <p>The real {@code DnsResolverService} can never be "running" in a test
 * run here — binding port 53 needs CAP_NET_BIND_SERVICE, and no %test
 * profile override exists for the port — so the "enabled and running"
 * branch is covered against {@link DashboardResource#dnsStatsFor}, the pure
 * decision function the endpoint itself delegates to, rather than through a
 * real HTTP round-trip against a listener that can't start here.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class DashboardDnsStatsTest {

    @Inject DnsQueryStats stats;

    @AfterEach
    @Transactional
    void restoreDefault() {
        Settings.<Settings>findById(Settings.SINGLETON_ID).dnsResolverEnabled = false;
    }

    @Test
    void dnsField_isNull_whenTheResolverIsOff() {
        setDnsResolverEnabled(false);

        given().when().get("/api/v1/dashboard")
                .then().statusCode(200)
                .body("dns", nullValue());
    }

    @Test
    void dnsField_isNull_whenEnabledButTheRealListenerIsntRunning() {
        // Port 53 needs CAP_NET_BIND_SERVICE, unavailable here, so the real
        // DnsResolverService is never actually running in a test run — this
        // pins that the dashboard still hides the tile in exactly that case.
        setDnsResolverEnabled(true);

        given().when().get("/api/v1/dashboard")
                .then().statusCode(200)
                .body("dns", nullValue());
    }

    @Test
    void dnsStatsFor_isNull_whenDisabledOrNotRunning() {
        DnsQueryStats.Snapshot snap = new DnsQueryStats.Snapshot(5, 3, 30);

        assertThat(DashboardResource.dnsStatsFor(false, true, snap)).isNull();
        assertThat(DashboardResource.dnsStatsFor(true, false, snap)).isNull();
        assertThat(DashboardResource.dnsStatsFor(false, false, snap)).isNull();
    }

    @Test
    void dnsStatsFor_reportsTheSnapshotsCounts_whenEnabledAndRunning() {
        DnsQueryStats.Snapshot snap = new DnsQueryStats.Snapshot(5, 3, 30);

        DashboardDto.DnsStats out = DashboardResource.dnsStatsFor(true, true, snap);

        assertThat(out).isNotNull();
        assertThat(out.local()).isEqualTo(5);
        assertThat(out.forwarded()).isEqualTo(3);
        assertThat(out.windowMinutes()).isEqualTo(30);
    }

    @Transactional
    void setDnsResolverEnabled(boolean enabled) {
        Settings.<Settings>findById(Settings.SINGLETON_ID).dnsResolverEnabled = enabled;
    }
}
