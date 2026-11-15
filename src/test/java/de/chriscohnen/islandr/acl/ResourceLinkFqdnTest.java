package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * resource-link-fqdn (1.4.0): a partner machine in an external network carries
 * the public name it is known by (icanhazip.com), and the portal links by it.
 * The name is the partner's, not the hub's: it sits outside the hub's zone, is
 * not unique (two addresses of one service), and is not for internal networks.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class ResourceLinkFqdnTest {

    private static final String MAIL = "linkfqdn@example.test";

    @PersistenceContext EntityManager em;
    @Inject ResourceService resources;
    @Inject SettingsService settings;

    private boolean savedLinks;

    @BeforeEach
    void remember() {
        savedLinks = QuarkusTransaction.requiringNew().call(() -> settings.get().portalLinksUseDnsName);
        wipe();
    }

    @AfterEach
    void restore() {
        wipe();
        QuarkusTransaction.requiringNew().run(() -> settings.get().portalLinksUseDnsName = savedLinks);
    }

    private void wipe() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM user_roles").executeUpdate();
            em.createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
            RoleResourceGrant.deleteAll();
            ResourcePort.deleteAll();
            Resource.deleteAll();
            Site.delete("name like ?1", "FqdnNet%");
            Role.delete("name", "FqdnRole");
            User.delete("email", MAIL);
        });
    }

    private Site site(String name, String cidr, String kind) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Site s = Site.createNew(name, cidr, null);
            s.kind = kind;
            s.persist();
            return s;
        });
    }

    private Resource resource(Site site, String ip, String linkFqdn) {
        return QuarkusTransaction.requiringNew().call(() -> resources.create(site.id,
                new ResourceDto.UpsertRequest("icanhazip", ip, null, "computer", "", false, null, null, null, linkFqdn)));
    }

    @Test
    void externalResource_keepsTheNameNormalised() {
        Site ext = site("FqdnNet1", "104.16.184.241/32", Site.KIND_EXTERNAL);
        assertThat(resource(ext, "104.16.184.241", " ICanHazIP.com. ").linkFqdn).isEqualTo("icanhazip.com");
    }

    @Test
    void twoResourcesMayShareOneName() {
        Site a = site("FqdnNet2", "104.16.184.241/32", Site.KIND_EXTERNAL);
        Site b = site("FqdnNet3", "104.16.185.241/32", Site.KIND_EXTERNAL);
        resource(a, "104.16.184.241", "icanhazip.com");
        assertThat(resource(b, "104.16.185.241", "icanhazip.com").linkFqdn).isEqualTo("icanhazip.com");
    }

    @Test
    void internalNetwork_refusesALinkName() {
        Site lan = site("FqdnNet4", "10.98.0.0/24", Site.KIND_INTERNAL);
        assertThatThrownBy(() -> resource(lan, "10.98.0.5", "icanhazip.com"))
                .isInstanceOf(WebApplicationException.class);
    }

    @Test
    void notAHostName_isRefused() {
        Site ext = site("FqdnNet5", "104.16.186.241/32", Site.KIND_EXTERNAL);
        for (String bad : new String[] {"icanhazip", "https://icanhazip.com", "a b.com", "-x.com"}) {
            assertThatThrownBy(() -> resource(ext, "104.16.186.241", bad))
                    .as(bad).isInstanceOf(WebApplicationException.class);
        }
    }

    @Test
    void portMayPickTheName_onlyWhenTheResourceHasOne() {
        Site ext = site("FqdnNet6", "104.16.187.241/32", Site.KIND_EXTERNAL);
        Resource with = resource(ext, "104.16.187.241", "icanhazip.com");
        ResourceDto.PortRequest pick = new ResourceDto.PortRequest(443, null, "tcp", "HTTPS", null, null,
                true, false, "native", null, null, null, "@fqdn");
        assertThat(QuarkusTransaction.requiringNew().call(() -> resources.addPort(with.id, pick)).linkHost)
                .isEqualTo("@fqdn");
        Resource without = resource(site("FqdnNet7", "104.16.188.241/32", Site.KIND_EXTERNAL), "104.16.188.241", null);
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().call(() -> resources.addPort(without.id, pick)))
                .isInstanceOf(WebApplicationException.class);
    }

    private String seedGranted(String linkFqdn, String portLinkHost) {
        return QuarkusTransaction.requiringNew().call(() -> {
            User user = User.createNew("Fqdn", MAIL);
            user.persist();
            Role role = Role.createNew("FqdnRole", null);
            role.persist();
            em.createNativeQuery("INSERT INTO user_roles (user_id, role_id) VALUES (?1, ?2)")
                    .setParameter(1, user.id).setParameter(2, role.id).executeUpdate();
            Site s = Site.createNew("FqdnNet8", "104.16.189.241/32", null);
            s.kind = Site.KIND_EXTERNAL;
            s.persist();
            Resource res = Resource.createNew(s.id, "icanhazip", "104.16.189.241", null, "computer");
            res.linkFqdn = linkFqdn;
            res.persist();
            ResourcePort port = ResourcePort.createNew(res.id, 443, null, "tcp", "HTTPS", null, null, true, false, "native");
            port.linkHost = portLinkHost;
            port.persist();
            RoleResourceGrant.createNew(role.id, res.id, true).persist();
            return user.id;
        });
    }

    private io.restassured.response.ValidatableResponse portal(String userId) {
        return given().queryParam("userId", userId).when().get("/api/v1/acl/my-resources").then().statusCode(200);
    }

    @Test
    void portal_linksByTheName_whenTheSwitchIsOn_evenWithTheResolverOff() {
        QuarkusTransaction.requiringNew().run(() -> {
            settings.get().dnsResolverEnabled = false;
            settings.get().portalLinksUseDnsName = true;
        });
        portal(seedGranted("icanhazip.com", null)).body("resources[0].grantedPorts[0].linkHost", is("icanhazip.com"));
    }

    @Test
    void portal_portChoosingTheIp_staysOnTheIp() {
        QuarkusTransaction.requiringNew().run(() -> settings.get().portalLinksUseDnsName = true);
        portal(seedGranted("icanhazip.com", "ip")).body("resources[0].grantedPorts[0].linkHost", nullValue());
    }

    @Test
    void portal_portChoosingTheName_linksByItWithTheSwitchOff() {
        QuarkusTransaction.requiringNew().run(() -> settings.get().portalLinksUseDnsName = false);
        portal(seedGranted("icanhazip.com", "@fqdn")).body("resources[0].grantedPorts[0].linkHost", is("icanhazip.com"));
    }
}
