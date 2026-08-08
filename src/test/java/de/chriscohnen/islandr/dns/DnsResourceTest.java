package de.chriscohnen.islandr.dns;

import de.chriscohnen.islandr.acl.Resource;
import de.chriscohnen.islandr.acl.Site;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.settings.Settings;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The REST surface behind the System → DNS status page — status summary and
 * the admin-only lookup preview, kept intentionally separate from the actual
 * resolver socket path ({@code DnsQueryHandlerTest} covers that).
 *
 * <p>Every fixture mutation runs in its own committed transaction
 * ({@code QuarkusTransaction.requiringNew()}) before the RestAssured HTTP
 * call — the request is served on a different thread/transaction than the
 * test method, so an uncommitted {@code @Transactional}-test-method change
 * (fine for in-process calls like {@code DnsQueryHandlerTest}) would simply
 * not be visible yet to the server handling the HTTP request.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class DnsResourceTest {

    private static final String ZONE = "islandr.internal";

    @AfterEach
    void resetResolverFlag() {
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.dnsResolverEnabled = false;
            s.dnsResolverZone = null;
            s.dnsResolverUpstream = null;
        });
    }

    @Test
    void status_reportsDisabledByDefault() {
        given().when().get("/api/v1/dns/status")
                .then().statusCode(200)
                .body("enabled", equalTo(false))
                .body("running", equalTo(false))
                .body("zone", equalTo(ZONE)) // effective default even while disabled
                .body("upstreams", notNullValue())
                .body("bindAddress", notNullValue())
                .body("port", equalTo(53));
    }

    @Test
    void status_reflectsEnabledFlagAndCustomZone() {
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.dnsResolverEnabled = true;
            s.dnsResolverZone = "custom.test";
        });

        given().when().get("/api/v1/dns/status")
                .then().statusCode(200)
                .body("enabled", equalTo(true))
                .body("zone", equalTo("custom.test"));
    }

    @Test
    void status_countsResourcesWithADnsName() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        QuarkusTransaction.requiringNew().run(() -> {
            Site site = Site.createNew("DnsStatusSite-" + suffix, "10.71.0.0/24", null);
            site.persist();
            Resource named = Resource.createNew(site.id, "Named-" + suffix, "10.71.0.5", null, "computer");
            named.dnsName = "named";
            named.persist();
            Resource unnamed = Resource.createNew(site.id, "Unnamed-" + suffix, "10.71.0.6", null, "computer");
            unnamed.persist(); // dnsName left null — must not count
        });

        given().when().get("/api/v1/dns/status")
                .then().statusCode(200)
                .body("resolvableCount", greaterThanOrEqualTo(1));
    }

    @Test
    void lookup_isNotManaged_whenResolverDisabled() {
        given().contentType("application/json")
                .body("{ \"name\": \"anything.islandr.internal\" }")
                .when().post("/api/v1/dns/lookup")
                .then().statusCode(200)
                .body("result", equalTo("not-managed"))
                .body("ip", nullValue());
    }

    @Test
    void lookup_answersWithoutRequiringAConnectedPeer() {
        // The whole point of the admin preview: no Peer/ACL needed, unlike
        // DnsQueryHandler#resolve (see DnsQueryHandlerTest for the ACL-gated path).
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String siteName = "LookupSite-" + suffix;
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.dnsResolverEnabled = true;
            s.dnsResolverZone = ZONE;

            Site site = Site.createNew(siteName, "10.72.0.0/24", null);
            site.persist();
            Resource resource = Resource.createNew(site.id, "Printer-" + suffix, "10.72.0.9", null, "printer");
            resource.dnsName = "printer";
            resource.persist();
        });

        String fqdn = "printer." + DnsQueryHandler.slugify(siteName) + "." + ZONE;
        given().contentType("application/json")
                .body("{ \"name\": \"" + fqdn + "\" }")
                .when().post("/api/v1/dns/lookup")
                .then().statusCode(200)
                .body("result", equalTo("answer"))
                .body("ip", equalTo("10.72.0.9"))
                .body("fqdn", equalTo(fqdn));
    }

    @Test
    void lookup_returnsTheCanonicalFqdn_evenWhenABareNameWasTyped() {
        // The response echoes the *matched* FQDN, not the shortcut the admin
        // actually typed — makes the zone-append/bare-name convenience visible
        // instead of leaving the admin to guess what was matched.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String siteName = "BareSite-" + suffix;
        String dnsName = "bareprinter-" + suffix;
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.dnsResolverEnabled = true;
            s.dnsResolverZone = ZONE;

            Site site = Site.createNew(siteName, "10.73.0.0/24", null);
            site.persist();
            Resource resource = Resource.createNew(site.id, "BarePrinter-" + suffix, "10.73.0.9", null, "printer");
            resource.dnsName = dnsName;
            resource.persist();
        });

        String expectedFqdn = dnsName + "." + DnsQueryHandler.slugify(siteName) + "." + ZONE;
        given().contentType("application/json")
                .body("{ \"name\": \"" + dnsName + "\" }") // bare name, no site/zone
                .when().post("/api/v1/dns/lookup")
                .then().statusCode(200)
                .body("result", equalTo("answer"))
                .body("fqdn", equalTo(expectedFqdn));
    }

    @Test
    void lookup_isNxDomain_forUnknownNameInsideTheZone() {
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(Settings.SINGLETON_ID);
            s.dnsResolverEnabled = true;
            s.dnsResolverZone = ZONE;
        });

        given().contentType("application/json")
                .body("{ \"name\": \"does-not-exist.no-such-site." + ZONE + "\" }")
                .when().post("/api/v1/dns/lookup")
                .then().statusCode(200)
                .body("result", equalTo("nxdomain"))
                .body("ip", nullValue());
    }
}
