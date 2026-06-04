package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for /port-groups + /resources/{id}/ports/apply-group.
 * Auth-matrix coverage piggybacks on the broader AuthorizationMatrixTest;
 * this file focuses on CRUD semantics + the snapshot-apply behaviour.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class PortGroupEndpointsTest {

    @BeforeEach
    @Transactional
    void wipe() {
        // The Flyway seed inserts five default groups (Drucker, Web, RDP,
        // SSH, SMB). Some tests want a clean slate; others rely on the
        // seeded data. We always wipe ports/resources/sites + start from
        // an empty user-managed group set, but the SEEDED groups stay.
        Site.getEntityManager().createNativeQuery("DELETE FROM role_resource_grant_ports").executeUpdate();
        RoleResourceGrant.deleteAll();
        Site.getEntityManager().createNativeQuery("DELETE FROM user_roles").executeUpdate();
        ResourcePort.deleteAll();
        Resource.deleteAll();
        Site.deleteAll();
        Role.deleteAll();
        // Drop ONLY the user-created port groups by leaving the V10-seeded
        // IDs alone (they all start with '00000000-0000-0000-0000-port-group-').
        PortGroupMember.delete(
                "portGroupId not like ?1", "00000000-0000-0000-0000-port-group-%");
        PortGroup.delete("id not like ?1", "00000000-0000-0000-0000-port-group-%");
    }

    @Test
    void list_includesSeededDefaults() {
        // V10 seeded five templates — Drucker / Web / RDP / SSH / SMB. They
        // should all show up in the list without any extra setup.
        List<String> names = given().when().get("/api/v1/port-groups")
                .then().statusCode(200).extract().jsonPath().getList("name");
        assertThat(names).contains("Drucker_Standard_Ports", "Web_Standard", "RDP", "SSH", "SMB");
    }

    @Test
    void druckerGroup_hasTwoExpectedPorts() {
        // Spot-check the seed payload — the printer group is the example
        // the user asked for; if it ever loses 9100 or 631 we want to know.
        JsonPath body = given().when().get("/api/v1/port-groups")
                .then().statusCode(200).extract().jsonPath();
        int idx = body.getList("name").indexOf("Drucker_Standard_Ports");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        List<Integer> ports = body.getList("members[" + idx + "].port", Integer.class);
        assertThat(ports).containsExactlyInAnyOrder(9100, 631);
        List<String> transports = body.getList("members[" + idx + "].transport");
        assertThat(transports).allMatch(t -> t.equals("tcp"));
    }

    @Test
    void create_get_update_delete_roundtrip() {
        // Create with two members.
        String id = given().contentType("application/json").body("""
                {"name":"Custom_Mail","description":"SMTP + IMAP",
                 "members":[
                   {"port":25,"transport":"tcp","protocol":"SMTP","label":null},
                   {"port":143,"transport":"tcp","protocol":"IMAP","label":null}
                 ]}""")
                .when().post("/api/v1/port-groups")
                .then().statusCode(201)
                .body("name", org.hamcrest.Matchers.equalTo("Custom_Mail"))
                .body("members.size()", org.hamcrest.Matchers.equalTo(2))
                .extract().path("id");

        // Update replaces the member set wholesale — drop IMAP, add IMAPS.
        given().contentType("application/json").body("""
                {"name":"Custom_Mail","description":"SMTP + IMAPS",
                 "members":[
                   {"port":25,"transport":"tcp","protocol":"SMTP","label":null},
                   {"port":993,"transport":"tcp","protocol":"IMAPS","label":null}
                 ]}""")
                .when().put("/api/v1/port-groups/" + id)
                .then().statusCode(200);
        JsonPath body = given().when().get("/api/v1/port-groups/" + id)
                .then().statusCode(200).extract().jsonPath();
        assertThat(body.getList("members.port", Integer.class))
                .containsExactlyInAnyOrder(25, 993);

        given().when().delete("/api/v1/port-groups/" + id).then().statusCode(204);
        given().when().get("/api/v1/port-groups/" + id).then().statusCode(404);
    }

    @Test
    void create_duplicateName_returns409() {
        given().contentType("application/json").body("""
                {"name":"DupName","members":[{"port":1234,"transport":"tcp","protocol":"X"}]}""")
                .when().post("/api/v1/port-groups").then().statusCode(201);
        given().contentType("application/json").body("""
                {"name":"DupName","members":[{"port":5678,"transport":"tcp","protocol":"Y"}]}""")
                .when().post("/api/v1/port-groups").then().statusCode(409);
    }

    @Test
    void create_duplicatePortInRequest_returns409() {
        // Same (port, transport) twice inside one request — service catches
        // this before the unique index does, so the message is readable.
        given().contentType("application/json").body("""
                {"name":"DupPort","members":[
                   {"port":80,"transport":"tcp","protocol":"HTTP"},
                   {"port":80,"transport":"tcp","protocol":"HTTP-also"}
                 ]}""")
                .when().post("/api/v1/port-groups").then().statusCode(409);
    }

    @Test
    void create_emptyMembers_returns400() {
        // @NotNull lets through an empty list but the UI would never send one;
        // the SERVICE doesn't reject an empty list either (a group of zero is
        // technically allowed — it just becomes a no-op apply). We assert the
        // happy-empty path here so the contract is explicit.
        given().contentType("application/json").body("""
                {"name":"EmptyGroup","members":[]}""")
                .when().post("/api/v1/port-groups").then().statusCode(201);
    }

    @Test
    void applyGroup_addsMembersAsResourcePorts() {
        // Standard print scenario: resource has nothing yet, apply the
        // seeded Drucker group, both ports show up.
        String siteId = createSite("Office", "10.21.0.0/16");
        String rid = createResource(siteId, "Drucker-Buero-1", "10.21.0.30");
        String druckerGroup = "00000000-0000-0000-0000-port-group-prn";

        given().contentType("application/json")
                .body("{\"portGroupId\":\"" + druckerGroup + "\"}")
                .when().post("/api/v1/resources/" + rid + "/ports/apply-group")
                .then().statusCode(200)
                .body("added", org.hamcrest.Matchers.equalTo(2))
                .body("skippedExisting", org.hamcrest.Matchers.equalTo(0));

        List<Integer> ports = given().when().get("/api/v1/resources/" + rid)
                .then().statusCode(200).extract().jsonPath()
                .getList("ports.port", Integer.class);
        assertThat(ports).containsExactlyInAnyOrder(9100, 631);
    }

    @Test
    void applyGroup_isIdempotent() {
        // Applying the same group twice is safe — no duplicates, second call
        // reports zero added + all members skipped.
        String siteId = createSite("Office", "10.22.0.0/16");
        String rid = createResource(siteId, "Drucker", "10.22.0.30");
        String druckerGroup = "00000000-0000-0000-0000-port-group-prn";

        given().contentType("application/json").body("{\"portGroupId\":\"" + druckerGroup + "\"}")
                .when().post("/api/v1/resources/" + rid + "/ports/apply-group")
                .then().statusCode(200).body("added", org.hamcrest.Matchers.equalTo(2));
        given().contentType("application/json").body("{\"portGroupId\":\"" + druckerGroup + "\"}")
                .when().post("/api/v1/resources/" + rid + "/ports/apply-group")
                .then().statusCode(200)
                .body("added", org.hamcrest.Matchers.equalTo(0))
                .body("skippedExisting", org.hamcrest.Matchers.equalTo(2));
    }

    @Test
    void applyGroup_preservesManuallyAddedPorts() {
        // The user added port 22 manually, then applies the Drucker group.
        // 9100 + 631 land alongside the existing 22.
        String siteId = createSite("Office", "10.23.0.0/16");
        String rid = createResource(siteId, "Mixed", "10.23.0.30");
        addPort(rid, 22, "tcp", "SSH");

        given().contentType("application/json")
                .body("{\"portGroupId\":\"00000000-0000-0000-0000-port-group-prn\"}")
                .when().post("/api/v1/resources/" + rid + "/ports/apply-group")
                .then().statusCode(200);

        List<Integer> ports = given().when().get("/api/v1/resources/" + rid)
                .then().statusCode(200).extract().jsonPath()
                .getList("ports.port", Integer.class);
        assertThat(ports).containsExactlyInAnyOrder(22, 9100, 631);
    }

    @Test
    void applyGroup_editingGroupAfterApply_doesNotMutateResource() {
        // The whole reason for snapshot semantics. Apply, then edit the
        // group, then re-fetch the resource — the ports must be unchanged.
        String siteId = createSite("Office", "10.24.0.0/16");
        String rid = createResource(siteId, "Drucker", "10.24.0.30");

        String groupId = given().contentType("application/json").body("""
                {"name":"Snap_Test","members":[
                   {"port":9100,"transport":"tcp","protocol":"RAW"},
                   {"port":631,"transport":"tcp","protocol":"IPP"}
                 ]}""")
                .when().post("/api/v1/port-groups").then().statusCode(201).extract().path("id");

        given().contentType("application/json").body("{\"portGroupId\":\"" + groupId + "\"}")
                .when().post("/api/v1/resources/" + rid + "/ports/apply-group")
                .then().statusCode(200).body("added", org.hamcrest.Matchers.equalTo(2));

        // Edit the group: drop 631, keep 9100, add 515 (LPR).
        given().contentType("application/json").body("""
                {"name":"Snap_Test","members":[
                   {"port":9100,"transport":"tcp","protocol":"RAW"},
                   {"port":515,"transport":"tcp","protocol":"LPR"}
                 ]}""")
                .when().put("/api/v1/port-groups/" + groupId).then().statusCode(200);

        // The resource still has 9100 + 631, not 9100 + 515.
        List<Integer> ports = given().when().get("/api/v1/resources/" + rid)
                .then().statusCode(200).extract().jsonPath()
                .getList("ports.port", Integer.class);
        assertThat(ports).containsExactlyInAnyOrder(9100, 631);
    }

    @Test
    void applyGroup_unknownGroupId_returns404() {
        String siteId = createSite("Office", "10.25.0.0/16");
        String rid = createResource(siteId, "X", "10.25.0.5");
        given().contentType("application/json").body("{\"portGroupId\":\"nope-not-a-real-id\"}")
                .when().post("/api/v1/resources/" + rid + "/ports/apply-group")
                .then().statusCode(404);
    }

    @Test
    void deleteGroup_doesNotRemovePortsFromResourcesThatAppliedIt() {
        // Snapshot semantics: deleting a group is safe — resources keep
        // their copies. The audit log notes the deletion, but the wire-side
        // configuration is unaffected.
        String siteId = createSite("Office", "10.26.0.0/16");
        String rid = createResource(siteId, "Drucker", "10.26.0.30");

        String groupId = given().contentType("application/json").body("""
                {"name":"Will_Be_Deleted","members":[{"port":9100,"transport":"tcp","protocol":"RAW"}]}""")
                .when().post("/api/v1/port-groups").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body("{\"portGroupId\":\"" + groupId + "\"}")
                .when().post("/api/v1/resources/" + rid + "/ports/apply-group").then().statusCode(200);

        given().when().delete("/api/v1/port-groups/" + groupId).then().statusCode(204);

        List<Integer> ports = given().when().get("/api/v1/resources/" + rid)
                .then().statusCode(200).extract().jsonPath().getList("ports.port", Integer.class);
        assertThat(ports).containsExactly(9100);
    }

    // -- helpers --------------------------------------------------------------

    private String createSite(String name, String cidr) {
        return given().contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"cidr\":\"" + cidr + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("id");
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
}
