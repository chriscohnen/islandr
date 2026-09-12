package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.wg.CleanWgInterfaceExtension;
import de.chriscohnen.islandr.wg.WgAdapter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hub adopted from an existing WireGuard setup carries peers Islandr does not
 * manage — Islandr never writes {@code /etc/wireguard/<iface>.conf}, so the
 * database alone does not know their addresses. Handing such an address out a
 * second time looks like it worked: {@code wg set} moves it to the new peer,
 * until the next interface reload gives it back to the file's peer and traffic
 * for that address reaches a device the admin does not manage.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
@ExtendWith(CleanWgInterfaceExtension.class)
class ForeignPeerAddressTest {

    private static final String FOREIGN_KEY = "FoReIgN00000000000000000000000000000000000A=";

    @Inject WgAdapter wg;

    @AfterEach
    @Transactional
    void wipe() {
        Peer.deleteAll();
        User.deleteAll();
    }

    private String createUser() {
        return given().contentType("application/json")
                .body("{\"name\":\"Felix\",\"email\":\"felix-" + UUID.randomUUID() + "@example.com\"}")
                .when().post("/api/v1/users").then().statusCode(201).extract().path("id");
    }

    @Test
    void creatingAPeerOnAnAddressAForeignPeerHolds_isRejected() {
        wg.setPeer("wg0", FOREIGN_KEY, "10.8.0.42/32", null);
        String userId = createUser();

        given().contentType("application/json")
                .body("{\"name\":\"collides\",\"assignedIp\":\"10.8.0.42\"}")
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(409);
    }

    @Test
    void suggestedAddressSkipsWhatAForeignPeerHolds() {
        // Take the address the allocator would otherwise return first.
        String first = given().when().get("/api/v1/peers/next-ip")
                .then().statusCode(200).extract().path("assignedIp");
        wg.setPeer("wg0", FOREIGN_KEY, first + "/32", null);

        String next = given().when().get("/api/v1/peers/next-ip")
                .then().statusCode(200).extract().path("assignedIp");

        assertThat(next)
                .as("the allocator must not offer an address a peer on the interface already holds")
                .isNotEqualTo(first);
    }

    @Test
    void importingAPeerFromTheInterfaceIsNotBlockedByItsOwnAddress() {
        // The candidate is on the interface and not in the database — which is
        // exactly what the collision check calls foreign. Without excluding the
        // key being imported, adopting an existing hub would reject every peer.
        wg.setPeer("wg0", FOREIGN_KEY, "10.8.0.44/32", null);
        createUser();

        given().contentType("application/json")
                .body("{\"peers\":[{\"publicKey\":\"" + FOREIGN_KEY + "\",\"name\":\"adopted\","
                        + "\"assignedIp\":\"10.8.0.44\",\"type\":\"client\"}]}")
                .when().post("/api/v1/peers/wg-import")
                .then().statusCode(200)
                .body("[0].status", org.hamcrest.Matchers.equalTo("imported"));
    }

    @Test
    void anAddressHeldByAManagedPeerIsStillGovernedByTheDatabase() {
        String userId = createUser();
        String ip = given().contentType("application/json")
                .body("{\"name\":\"managed\",\"assignedIp\":\"10.8.0.43\"}")
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(201).extract().path("peer.assignedIp");

        // Same address again: rejected by the database rule, not the interface one.
        given().contentType("application/json")
                .body("{\"name\":\"dupe\",\"assignedIp\":\"" + ip + "\"}")
                .when().post("/api/v1/users/" + userId + "/peers")
                .then().statusCode(409);
    }
}
