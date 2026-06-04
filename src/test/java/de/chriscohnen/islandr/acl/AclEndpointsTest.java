package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the whole ACL domain: sites, resources, ports,
 * roles, memberships, and the grant matrix. One file because the entities
 * are tightly coupled — most useful flows traverse several of them.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class AclEndpointsTest {

    @BeforeEach
    @Transactional
    void wipe() {
        // Order matters because of FKs. Grants → ports → resources → sites,
        // and user_roles + roles last.
        Site.getEntityManager().createNativeQuery(
                "DELETE FROM role_resource_grant_ports").executeUpdate();
        RoleResourceGrant.deleteAll();
        Site.getEntityManager().createNativeQuery(
                "DELETE FROM user_roles").executeUpdate();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
    }

    // -- Sites ----------------------------------------------------------------

    @Test
    void site_create_get_update_delete_roundtrip() {
        String id = given().contentType("application/json")
                .body("{\"name\":\"HQ\",\"cidr\":\"10.20.0.0/16\",\"description\":\"head office\"}")
                .when().post("/api/v1/sites")
                .then().statusCode(201)
                .body("name", org.hamcrest.Matchers.equalTo("HQ"))
                .extract().path("id");

        given().when().get("/api/v1/sites/" + id).then().statusCode(200)
                .body("cidr", org.hamcrest.Matchers.equalTo("10.20.0.0/16"));

        given().contentType("application/json")
                .body("{\"name\":\"HQ Hamburg\",\"cidr\":\"10.20.0.0/16\",\"description\":\"renamed\"}")
                .when().put("/api/v1/sites/" + id).then().statusCode(200)
                .body("name", org.hamcrest.Matchers.equalTo("HQ Hamburg"));

        given().when().delete("/api/v1/sites/" + id).then().statusCode(204);
        given().when().get("/api/v1/sites/" + id).then().statusCode(404);
    }

    @Test
    void site_create_duplicateName_returns409() {
        createSite("Berlin", "10.30.0.0/16");
        given().contentType("application/json")
                .body("{\"name\":\"Berlin\",\"cidr\":\"10.40.0.0/16\"}")
                .when().post("/api/v1/sites").then().statusCode(409);
    }

    @Test
    void site_delete_blockedByResources_returns409() {
        String siteId = createSite("DataCenter", "10.50.0.0/16");
        createResource(siteId, "TerminalDC-1", "10.50.0.5");
        given().when().delete("/api/v1/sites/" + siteId).then().statusCode(409);
    }

    @Test
    void site_invalidCidr_returns400() {
        given().contentType("application/json")
                .body("{\"name\":\"Bad\",\"cidr\":\"not-a-cidr\"}")
                .when().post("/api/v1/sites").then().statusCode(400);
    }

    // -- Resources + Ports ----------------------------------------------------

    @Test
    void resource_create_listForSite_returnsIt() {
        String siteId = createSite("Office", "10.21.0.0/16");
        String rid = createResource(siteId, "Terminal-01", "10.21.0.5");
        JsonPath body = given().when().get("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(200).extract().jsonPath();
        assertThat(body.getList("id", String.class)).contains(rid);
        assertThat(body.getList("ip", String.class)).contains("10.21.0.5");
    }

    @Test
    void resource_create_duplicateIpInSite_returns409() {
        String siteId = createSite("HQ2", "10.22.0.0/16");
        createResource(siteId, "T-01", "10.22.0.5");
        given().contentType("application/json")
                .body("{\"name\":\"T-01-clone\",\"ip\":\"10.22.0.5\"}")
                .when().post("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(409);
    }

    @Test
    void resource_create_sameIpDifferentSite_isAllowed() {
        String s1 = createSite("S1", "10.23.0.0/16");
        String s2 = createSite("S2", "10.24.0.0/16");
        createResource(s1, "R1", "192.168.1.10");
        // Second site can legitimately reuse the private IP — different remote LAN.
        given().contentType("application/json")
                .body("{\"name\":\"R-also\",\"ip\":\"192.168.1.10\"}")
                .when().post("/api/v1/sites/" + s2 + "/resources").then().statusCode(201);
    }

    @Test
    void resource_addPort_listsUnderResource() {
        String siteId = createSite("S", "10.25.0.0/16");
        String rid = createResource(siteId, "R", "10.25.0.5");
        given().contentType("application/json")
                .body("{\"port\":3389,\"transport\":\"tcp\",\"protocol\":\"RDP\",\"label\":\"RDP\"}")
                .when().post("/api/v1/resources/" + rid + "/ports").then().statusCode(200);
        given().when().get("/api/v1/resources/" + rid)
                .then().statusCode(200)
                .body("ports[0].port", org.hamcrest.Matchers.equalTo(3389))
                .body("ports[0].protocol", org.hamcrest.Matchers.equalTo("RDP"));
    }

    @Test
    void resource_addPort_duplicate_returns409() {
        String siteId = createSite("S", "10.26.0.0/16");
        String rid = createResource(siteId, "R", "10.26.0.5");
        given().contentType("application/json")
                .body("{\"port\":22,\"transport\":\"tcp\",\"protocol\":\"SSH\"}")
                .when().post("/api/v1/resources/" + rid + "/ports").then().statusCode(200);
        given().contentType("application/json")
                .body("{\"port\":22,\"transport\":\"tcp\",\"protocol\":\"SSH-2\"}")
                .when().post("/api/v1/resources/" + rid + "/ports").then().statusCode(409);
    }

    @Test
    void resource_delete_cascadesPorts() {
        String siteId = createSite("S", "10.27.0.0/16");
        String rid = createResource(siteId, "R", "10.27.0.5");
        addPort(rid, 80, "tcp", "HTTP");
        addPort(rid, 443, "tcp", "HTTPS");
        given().when().delete("/api/v1/resources/" + rid).then().statusCode(204);
        // FK cascade should have removed the port rows; total port count for the
        // site's (now empty) resource set is zero.
        assertThat(countPortsForResource(rid)).isZero();
    }

    // -- Roles ---------------------------------------------------------------

    @Test
    void role_create_list_update_delete() {
        String id = given().contentType("application/json")
                .body("{\"name\":\"Vertrieb\",\"description\":\"Sales team\"}")
                .when().post("/api/v1/roles").then().statusCode(201)
                .extract().path("id");
        given().when().get("/api/v1/roles").then().statusCode(200)
                .body("name", org.hamcrest.Matchers.hasItem("Vertrieb"));
        given().contentType("application/json")
                .body("{\"name\":\"Vertrieb DE\",\"description\":\"Sales DE\"}")
                .when().put("/api/v1/roles/" + id).then().statusCode(200);
        given().when().delete("/api/v1/roles/" + id).then().statusCode(204);
    }

    @Test
    void role_members_putReplacesSet() {
        String roleId = createRole("IT");
        String u1 = persistUser("alice");
        String u2 = persistUser("bob");
        // PUT [u1, u2] — both added.
        given().contentType("application/json")
                .body("{\"userIds\":[\"" + u1 + "\",\"" + u2 + "\"]}")
                .when().put("/api/v1/roles/" + roleId + "/users").then().statusCode(204);
        given().when().get("/api/v1/roles/" + roleId + "/users").then().statusCode(200)
                .body("id.size()", org.hamcrest.Matchers.equalTo(2));
        // PUT [u1] — u2 removed.
        given().contentType("application/json").body("{\"userIds\":[\"" + u1 + "\"]}")
                .when().put("/api/v1/roles/" + roleId + "/users").then().statusCode(204);
        given().when().get("/api/v1/roles/" + roleId + "/users").then().statusCode(200)
                .body("id.size()", org.hamcrest.Matchers.equalTo(1))
                .body("id[0]", org.hamcrest.Matchers.equalTo(u1));
    }

    @Test
    void role_members_unknownUserId_returns404() {
        String roleId = createRole("X");
        given().contentType("application/json")
                .body("{\"userIds\":[\"nope-not-a-real-id\"]}")
                .when().put("/api/v1/roles/" + roleId + "/users").then().statusCode(404);
    }

    // -- Matrix ---------------------------------------------------------------

    @Test
    void matrix_empty_initially() {
        given().when().get("/api/v1/acl/matrix").then().statusCode(200)
                .body("$.size()", org.hamcrest.Matchers.equalTo(0));
    }

    @Test
    void matrix_apply_createsGrants_thenReadBack() {
        String siteId = createSite("S", "10.28.0.0/16");
        String rid = createResource(siteId, "R", "10.28.0.5");
        String pid = addPort(rid, 22, "tcp", "SSH");
        String roleId = createRole("IT");

        // Grant allPorts=false with one specific port.
        String body = "{\"grants\":[{"
                + "\"roleId\":\"" + roleId + "\","
                + "\"resourceId\":\"" + rid + "\","
                + "\"allPorts\":false,"
                + "\"portIds\":[\"" + pid + "\"]"
                + "}]}";
        given().contentType("application/json").body(body)
                .when().put("/api/v1/acl/matrix").then().statusCode(200)
                .body("changed", org.hamcrest.Matchers.equalTo(1));

        JsonPath m = given().when().get("/api/v1/acl/matrix").then().statusCode(200).extract().jsonPath();
        assertThat(m.getList("$").size()).isEqualTo(1);
        assertThat(m.getString("[0].roleId")).isEqualTo(roleId);
        assertThat(m.getBoolean("[0].allPorts")).isFalse();
        assertThat(m.getList("[0].portIds", String.class)).containsExactly(pid);
    }

    @Test
    void matrix_apply_switchToAllPorts_clearsLimitedPorts() {
        String siteId = createSite("S", "10.29.0.0/16");
        String rid = createResource(siteId, "R", "10.29.0.5");
        String pid = addPort(rid, 22, "tcp", "SSH");
        String roleId = createRole("Engineer");

        applyGrant(roleId, rid, false, List.of(pid));
        // Then upgrade to allPorts=true — port-specific row must be cleared.
        applyGrant(roleId, rid, true, List.of());
        JsonPath m = given().when().get("/api/v1/acl/matrix").then().statusCode(200).extract().jsonPath();
        assertThat(m.getBoolean("[0].allPorts")).isTrue();
        assertThat(m.getList("[0].portIds", String.class)).isEmpty();
    }

    @Test
    void matrix_apply_emptyPortsAndNotAllPorts_deletesGrant() {
        String siteId = createSite("S", "10.30.0.0/16");
        String rid = createResource(siteId, "R", "10.30.0.5");
        String pid = addPort(rid, 22, "tcp", "SSH");
        String roleId = createRole("R");
        applyGrant(roleId, rid, false, List.of(pid));
        // ∅ tri-state: allPorts=false + empty portIds means remove the grant.
        applyGrant(roleId, rid, false, List.of());
        given().when().get("/api/v1/acl/matrix").then().statusCode(200)
                .body("$.size()", org.hamcrest.Matchers.equalTo(0));
    }

    @Test
    void matrix_apply_portIdNotBelongingToResource_returns400() {
        String s1 = createSite("S1", "10.31.0.0/16");
        String s2 = createSite("S2", "10.32.0.0/16");
        String r1 = createResource(s1, "R1", "10.31.0.5");
        String r2 = createResource(s2, "R2", "10.32.0.5");
        String foreignPort = addPort(r2, 22, "tcp", "SSH");
        String roleId = createRole("R");
        String body = "{\"grants\":[{"
                + "\"roleId\":\"" + roleId + "\","
                + "\"resourceId\":\"" + r1 + "\","
                + "\"allPorts\":false,"
                + "\"portIds\":[\"" + foreignPort + "\"]"
                + "}]}";
        given().contentType("application/json").body(body)
                .when().put("/api/v1/acl/matrix").then().statusCode(400);
    }

    @Test
    void matrix_apply_idempotent_noChange_returnsZero() {
        // Empty body, no changes — server says 'changed: 0'.
        given().contentType("application/json").body("{\"grants\":[]}")
                .when().put("/api/v1/acl/matrix").then().statusCode(200)
                .body("changed", org.hamcrest.Matchers.equalTo(0));
    }

    @Test
    void role_delete_cascadesGrants() {
        String siteId = createSite("S", "10.33.0.0/16");
        String rid = createResource(siteId, "R", "10.33.0.5");
        String pid = addPort(rid, 22, "tcp", "SSH");
        String roleId = createRole("Doomed");
        applyGrant(roleId, rid, false, List.of(pid));
        given().when().delete("/api/v1/roles/" + roleId).then().statusCode(204);
        // Grant rows + grant_ports rows should be gone.
        given().when().get("/api/v1/acl/matrix").then().statusCode(200)
                .body("$.size()", org.hamcrest.Matchers.equalTo(0));
    }

    // -- helpers --------------------------------------------------------------

    private String createSite(String name, String cidr) {
        return given().contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"cidr\":\"" + cidr + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201)
                .extract().path("id");
    }

    private String createResource(String siteId, String name, String ip) {
        return given().contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"ip\":\"" + ip + "\"}")
                .when().post("/api/v1/sites/" + siteId + "/resources")
                .then().statusCode(201).extract().path("id");
    }

    private String addPort(String resourceId, int port, String transport, String protocol) {
        return given().contentType("application/json")
                .body("{\"port\":" + port + ",\"transport\":\"" + transport
                        + "\",\"protocol\":\"" + protocol + "\"}")
                .when().post("/api/v1/resources/" + resourceId + "/ports")
                .then().statusCode(200).extract().path("id");
    }

    private String createRole(String name) {
        return given().contentType("application/json")
                .body("{\"name\":\"" + name + "\"}")
                .when().post("/api/v1/roles").then().statusCode(201).extract().path("id");
    }

    @Transactional
    String persistUser(String name) {
        User u = User.createNew(name + "-" + UUID.randomUUID(),
                name + "-" + UUID.randomUUID() + "@firma.de");
        u.persist();
        return u.id;
    }

    private void applyGrant(String roleId, String resourceId, boolean allPorts, List<String> portIds) {
        String portsArr = portIds.isEmpty() ? "[]"
                : "[" + String.join(",", portIds.stream().map(p -> "\"" + p + "\"").toList()) + "]";
        String body = "{\"grants\":[{"
                + "\"roleId\":\"" + roleId + "\","
                + "\"resourceId\":\"" + resourceId + "\","
                + "\"allPorts\":" + allPorts + ","
                + "\"portIds\":" + portsArr
                + "}]}";
        given().contentType("application/json").body(body)
                .when().put("/api/v1/acl/matrix").then().statusCode(200);
    }

    @Transactional
    long countPortsForResource(String resourceId) {
        return ResourcePort.count("resourceId", resourceId);
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> ignore;
}
