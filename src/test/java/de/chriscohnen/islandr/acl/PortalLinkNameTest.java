package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * resource-links-by-name (1.4.0): the portal links a port by the resource's
 * DNS name instead of its IP — a global switch (off by default) and a
 * per-port override (follow / IP / one of the resource's own names). The name
 * is the resolver's own ({@code DnsQueryHandler.resourceNames}).
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PortalLinkNameTest {

    private static final String MAIL = "linkname@example.test";

    @PersistenceContext EntityManager em;
    @Inject SettingsService settings;

    private boolean savedResolver;
    private boolean savedLinks;

    @BeforeEach
    void remember() {
        QuarkusTransaction.requiringNew().run(() -> {
            savedResolver = settings.get().dnsResolverEnabled;
            savedLinks = settings.get().portalLinksUseDnsName;
        });
        wipe();
    }

    @AfterEach
    void restore() {
        wipe();
        QuarkusTransaction.requiringNew().run(() -> {
            settings.get().dnsResolverEnabled = savedResolver;
            settings.get().portalLinksUseDnsName = savedLinks;
        });
    }

    private void wipe() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
            em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
            RoleResourceGrant.deleteAll();
            ResourcePort.deleteAll();
            Resource.deleteAll();
            Site.delete("name", "LinkNet");
            Role.delete("name", "LinkRole");
            User.delete("email", MAIL);
        });
    }

    private void switches(boolean resolver, boolean links) {
        QuarkusTransaction.requiringNew().run(() -> {
            settings.get().dnsResolverEnabled = resolver;
            settings.get().portalLinksUseDnsName = links;
        });
    }

    private record Seed(String userId, String portId) {}

    /** One granted resource "nas" (alias "files") with one port carrying the given override. */
    private Seed seed(String dnsName, String linkHost) {
        return QuarkusTransaction.requiringNew().call(() -> {
            User user = User.createNew("Link", MAIL);
            user.persist();
            Role role = Role.createNew("LinkRole", null);
            role.persist();
            em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                    .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
            Site site = Site.createNew("LinkNet", "10.97.0.0/24", null);
            site.persist();
            Resource res = Resource.createNew(site.id, "LinkNas", "10.97.0.5", null, "nas");
            res.dnsName = dnsName;
            if (dnsName != null) res.dnsAliases.add("files");
            res.persist();
            ResourcePort port = ResourcePort.createNew(res.id, 443, null, "tcp", "HTTPS", null, null,
                    true, false, "native");
            port.linkHost = linkHost;
            port.persist();
            RoleResourceGrant.createNew(role.id, res.id, true).persist();
            return new Seed(user.id, port.id);
        });
    }

    private io.restassured.response.ValidatableResponse portal(Seed s) {
        return given().queryParam("userId", s.userId())
                .when().get("/api/v1/acl/my-resources").then().statusCode(200);
    }

    @Test
    void switchOff_linksUseTheIp() {
        switches(true, false);
        portal(seed("nas", null)).body("resources[0].grantedPorts[0].linkHost", nullValue());
    }

    @Test
    void switchOn_withAName_linksUseTheMainName() {
        switches(true, true);
        portal(seed("nas", null))
                .body("resources[0].grantedPorts[0].linkHost", startsWith("nas."));
    }

    @Test
    void switchOn_withoutAName_fallsBackToTheIp() {
        switches(true, true);
        portal(seed(null, null)).body("resources[0].grantedPorts[0].linkHost", nullValue());
    }

    @Test
    void switchOn_butResolverOff_theNameWouldNotResolve_soTheIp() {
        switches(false, true);
        portal(seed("nas", null)).body("resources[0].grantedPorts[0].linkHost", nullValue());
    }

    @Test
    void portAlias_winsOverTheSwitch_evenWithTheSwitchOff() {
        switches(true, false);
        portal(seed("nas", "files"))
                .body("resources[0].grantedPorts[0].linkHost", startsWith("files."));
    }

    @Test
    void portOverrideIp_winsOverTheSwitch() {
        switches(true, true);
        portal(seed("nas", "ip")).body("resources[0].grantedPorts[0].linkHost", nullValue());
    }

    @Test
    void portRequest_refusesAnameTheResourceDoesNotHave_andAcceptsItsOwn() {
        Seed s = seed("nas", null);
        String resourceId = QuarkusTransaction.requiringNew().call(() -> ResourcePort.<ResourcePort>findById(s.portId()).resourceId);
        String body = "{\"port\":8443,\"transport\":\"tcp\",\"protocol\":\"HTTPS\",\"linkHost\":\"%s\"}";
        given().contentType("application/json").body(String.format(body, "evil"))
                .when().post("/api/v1/resources/" + resourceId + "/ports").then().statusCode(400);
        given().contentType("application/json").body(String.format(body, "files"))
                .when().post("/api/v1/resources/" + resourceId + "/ports").then().statusCode(200)
                .body("linkHost", is("files"));
    }

    @Test
    void portalLinksSwitch_isItsOwnEndpoint_defaultOff() {
        given().contentType("application/json").body("{\"portalLinksUseDnsName\":true}")
                .when().put("/api/v1/settings/portal-links").then().statusCode(200)
                .body("portalLinksUseDnsName", is(true));
    }
}
