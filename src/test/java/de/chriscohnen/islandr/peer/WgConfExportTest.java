package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Islandr configures peers with {@code wg set} and never writes
 * {@code /etc/wireguard/<iface>.conf} — so uninstalling it would take every peer
 * it configured with it. The export is what makes removal survivable: the admin
 * appends the blocks by hand and the tunnel outlives the service.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class WgConfExportTest {

    @BeforeEach
    @AfterEach
    @Transactional
    void wipe() {
        Peer.deleteAll();
        User.deleteAll();
    }

    @Test
    void export_rendersEnabledPeersAndLeavesTheInterfaceAlone() {
        String key = "ExPoRt00000000000000000000000000000000000A=";
        createPeer("export-me", key, "10.81.0.5", true);

        String body = given().when().get("/api/v1/peers/wg-conf-export")
                .then().statusCode(200).extract().asString();

        assertThat(body).contains("[Peer]", "PublicKey = " + key, "AllowedIPs = 10.81.0.5/32");
        // The header comment mentions [Interface] on purpose, so assert on
        // section lines rather than on the substring.
        assertThat(body.lines().map(String::strip))
                .as("the interface section stays the admin's — exporting it would be exactly the takeover Islandr avoids")
                .doesNotContain("[Interface]");
    }

    @Test
    void export_omitsDisabledPeers() {
        String off = "DiSaBlEd000000000000000000000000000000000B=";
        createPeer("switched-off", off, "10.81.0.6", false);

        String body = given().when().get("/api/v1/peers/wg-conf-export")
                .then().statusCode(200).extract().asString();

        assertThat(body)
                .as("a peer that is off on purpose must not be revived by an append")
                .doesNotContain(off);
    }

    @Test
    void export_requiresAdmin() {
        given().filter((req, spec, ctx) -> {
            req.removeCookies();
            return ctx.next(req, spec);
        }).when().get("/api/v1/peers/wg-conf-export").then().statusCode(401);
    }

    @Transactional
    void createPeer(String name, String publicKey, String ip, boolean enabled) {
        User u = User.createNew("Owner " + UUID.randomUUID(), "owner-" + UUID.randomUUID() + "@firma.de");
        u.persist();
        Peer p = new Peer();
        p.id = UUID.randomUUID().toString();
        p.userId = u.id;
        p.name = name;
        p.publicKey = publicKey;
        p.assignedIp = ip;
        p.enabled = enabled;
        p.type = "client";
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.persist();
    }
}
