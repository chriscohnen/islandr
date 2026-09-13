package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Issue #82: editing a port used to mean deleting and re-creating it, which
 * cascades — every port-scoped grant on that port is revoked, silently, and
 * the replacement is a different row nothing points at. {@code PUT
 * /api/v1/resources/{id}/ports/{portId}} exists precisely so that does not
 * happen; these tests pin the properties the UI now depends on.
 *
 * <p>Fixtures use a random suffix and clean up after themselves rather than
 * wiping the tables, so this class cannot decide the outcome of another one.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PortUpdateResourceTest {

    @PersistenceContext EntityManager em;

    private record Fixture(String siteId, String resourceId, String portId, String roleId, String grantId) {}

    @Transactional
    Fixture seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Site site = Site.createNew("PortEditSite-" + suffix, "10.65.0.0/16", null);
        site.persist();
        Resource res = Resource.createNew(site.id, "PortEditHost-" + suffix, "10.65.0.9", null, "rackserver");
        res.persist();
        ResourcePort p = ResourcePort.createNew(res.id, 8443, null, "tcp", "HTTPS",
                "Intranet", "/admin", false, false, "native");
        p.maxConcurrentUsers = 2;
        p.maxReservationMinutes = 30;
        p.autoApproveReservations = false;
        p.persist();

        Role role = Role.createNew("PortEditRole-" + suffix, null);
        role.persist();
        RoleResourceGrant g = RoleResourceGrant.createNew(role.id, res.id, false);
        g.persist();
        em.createNativeQuery("INSERT INTO role_resource_grant_ports (grant_id, port_id) VALUES (?1, ?2)")
                .setParameter(1, g.id).setParameter(2, p.id).executeUpdate();

        return new Fixture(site.id, res.id, p.id, role.id, g.id);
    }

    @Transactional
    void cleanup(Fixture f) {
        em.createNativeQuery("DELETE FROM role_resource_grant_ports WHERE grant_id = ?1")
                .setParameter(1, f.grantId()).executeUpdate();
        RoleResourceGrant.deleteById(f.grantId());
        Role.deleteById(f.roleId());
        ResourcePort.delete("resourceId = ?1", f.resourceId());
        Resource.deleteById(f.resourceId());
        Site.deleteById(f.siteId());
    }

    @Transactional
    long grantPortRows(String grantId, String portId) {
        Number n = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM role_resource_grant_ports WHERE grant_id = ?1 AND port_id = ?2")
                .setParameter(1, grantId).setParameter(2, portId).getSingleResult();
        return n.longValue();
    }

    /** The whole point of the endpoint: the row survives, so the grant does. */
    @Test
    void editingAPort_keepsThePortScopedGrantPointingAtIt() {
        Fixture f = seed();
        try {
            given().contentType("application/json")
                    .body("{\"port\":8443,\"portEnd\":null,\"transport\":\"tcp\",\"protocol\":\"HTTPS\","
                            + "\"label\":\"Intranet\",\"pathPrefix\":\"/console\","
                            + "\"maxConcurrentUsers\":2,\"maxReservationMinutes\":30,"
                            + "\"autoApproveReservations\":false}")
                    .when().put("/api/v1/resources/" + f.resourceId() + "/ports/" + f.portId())
                    .then().statusCode(200)
                    .body("id", equalTo(f.portId()))
                    .body("pathPrefix", equalTo("/console"));

            assertThat(grantPortRows(f.grantId(), f.portId()))
                    .as("port-scoped grant must still point at the edited port")
                    .isEqualTo(1);
        } finally {
            cleanup(f);
        }
    }

    /** Every stored field round-trips, including the ones the list never showed. */
    @Test
    void editingAPort_writesEveryConfiguredField() {
        Fixture f = seed();
        try {
            given().contentType("application/json")
                    .body("{\"port\":3389,\"portEnd\":null,\"transport\":\"tcp\",\"protocol\":\"RDP\","
                            + "\"label\":\"Terminal\",\"pathPrefix\":null,"
                            + "\"rdpClipboard\":true,\"rdpFileTransfer\":true,\"rdpAccessMode\":\"web-only\","
                            + "\"maxConcurrentUsers\":1,\"maxReservationMinutes\":15,"
                            + "\"autoApproveReservations\":true}")
                    .when().put("/api/v1/resources/" + f.resourceId() + "/ports/" + f.portId())
                    .then().statusCode(200)
                    .body("port", equalTo(3389))
                    .body("protocol", equalTo("RDP"))
                    .body("label", equalTo("Terminal"))
                    .body("pathPrefix", nullValue())
                    .body("rdpClipboard", equalTo(true))
                    .body("rdpFileTransfer", equalTo(true))
                    .body("rdpAccessMode", equalTo("web-only"))
                    .body("maxConcurrentUsers", equalTo(1))
                    .body("maxReservationMinutes", equalTo(15))
                    .body("autoApproveReservations", equalTo(true));
        } finally {
            cleanup(f);
        }
    }

    /** A collision is reported and nothing is changed — the UI shows this text. */
    @Test
    void editingAPort_ontoAnExistingPortNumber_conflicts() {
        Fixture f = seed();
        String otherPortId = addPort(f.resourceId());
        try {
            given().contentType("application/json")
                    .body("{\"port\":22,\"portEnd\":null,\"transport\":\"tcp\",\"protocol\":\"HTTPS\","
                            + "\"label\":null,\"pathPrefix\":null}")
                    .when().put("/api/v1/resources/" + f.resourceId() + "/ports/" + f.portId())
                    .then().statusCode(409);

            given().when().get("/api/v1/resources/" + f.resourceId())
                    .then().statusCode(200)
                    .body("ports.find { it.id == '" + f.portId() + "' }.port", equalTo(8443));
        } finally {
            deletePort(otherPortId);
            cleanup(f);
        }
    }

    @Transactional
    String addPort(String resourceId) {
        ResourcePort p = ResourcePort.createNew(resourceId, 22, null, "tcp", "SSH",
                null, null, false, false, "native");
        p.persist();
        return p.id;
    }

    @Transactional
    void deletePort(String portId) {
        ResourcePort.deleteById(portId);
    }
}
