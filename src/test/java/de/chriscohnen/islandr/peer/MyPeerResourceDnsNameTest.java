package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.auth.Session;
import de.chriscohnen.islandr.auth.SessionFilter;
import de.chriscohnen.islandr.auth.SessionService;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;

/**
 * myaccess-peer-dns-name-display: {@code GET /api/v1/peers/mine} shows each
 * peer's own, already-slugified DNS name (null when the resolver is off) —
 * the data half of showing it on the "Mein Zugang" page.
 */
@QuarkusTest
class MyPeerResourceDnsNameTest {

    @Inject SessionService sessions;

    private static final String ZONE = "islandr.internal";

    @BeforeEach
    void resetRestAssuredDefaults() {
        RestAssured.requestSpecification = null;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = false;
        s.dnsResolverZone = null;
    }

    @Test
    void dnsFqdn_isPopulated_whenResolverEnabled() {
        enableResolver();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String userId = persistUser(suffix);
        String cookie = sessions.create(Session.MICROSOFT, "principal-" + suffix, userId).id;
        // "My-Laptop-<suffix>" slugifies predictably (lowercased, spaces to
        // hyphens) — asserting the literal expected value rather than
        // calling DnsQueryHandler.slugify() directly, which is deliberately
        // package-private (see its own doc comment).
        persistPeer(userId, "My Laptop " + suffix);

        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(200)
                .body("[0].dnsFqdn", org.hamcrest.Matchers.equalTo("my-laptop-" + suffix + "." + ZONE));
    }

    @Test
    void dnsFqdn_isNull_whenResolverDisabled() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String userId = persistUser(suffix);
        String cookie = sessions.create(Session.MICROSOFT, "principal-" + suffix, userId).id;
        persistPeer(userId, "Device-" + suffix);

        given().cookie(SessionFilter.COOKIE_NAME, cookie)
                .when().get("/api/v1/peers/mine")
                .then().statusCode(200)
                .body("[0].dnsFqdn", nullValue());
    }

    @Transactional
    void enableResolver() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        s.dnsResolverEnabled = true;
        s.dnsResolverZone = ZONE;
    }

    @Transactional
    String persistUser(String suffix) {
        User u = User.createNew("DnsName-User-" + suffix, "dnsname-" + suffix + "@firma.de");
        u.isAdmin = false;
        u.persist();
        return u.id;
    }

    @Transactional
    void persistPeer(String userId, String deviceName) {
        Peer.createNew(userId, deviceName, fakeKey(deviceName), "10.9.0." + (1 + (int) (Math.random() * 200))).persist();
    }

    private static String fakeKey(String seed) {
        String raw = ("KEYdns" + seed).repeat(4);
        return raw.substring(0, 43) + "=";
    }
}
