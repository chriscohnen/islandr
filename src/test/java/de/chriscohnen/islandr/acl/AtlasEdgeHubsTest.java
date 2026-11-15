package de.chriscohnen.islandr.acl;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.settings.SettingsService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * atlas-edge-hubs (1.4.0): the Atlas graph says which hub serves each network
 * so the diagram can draw one hub node per hub. With edge hubs off, every
 * network belongs to the central hub and no edge hub is listed.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class AtlasEdgeHubsTest {

    private static final String MARK = "atlasedge-";

    @Inject SettingsService settings;

    private boolean saved;
    private String hubId;
    private String edgeSiteId;
    private String centralSiteId;

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            saved = settings.get().edgeHubsEnabled;
            EdgeHub h = EdgeHub.createNew(MARK + "berlin", 52.5, 13.4, null);
            h.persist();
            hubId = h.id;
            Site edge = Site.createNew(MARK + "edge-net", "10.93.0.0/24", null);
            edge.edgeHubId = h.id;
            edge.persist();
            edgeSiteId = edge.id;
            Site central = Site.createNew(MARK + "central-net", "10.94.0.0/24", null);
            central.persist();
            centralSiteId = central.id;
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            Site.delete("name like ?1", MARK + "%");
            EdgeHub.delete("name like ?1", MARK + "%");
            settings.get().edgeHubsEnabled = saved;
        });
    }

    private void edgeHubs(boolean on) {
        QuarkusTransaction.requiringNew().run(() -> settings.get().edgeHubsEnabled = on);
    }

    @Test
    void withEdgeHubsOn_siteNamesItsHub_andTheHubIsListed() {
        edgeHubs(true);
        given().when().get("/api/v1/acl/atlas").then().statusCode(200)
                .body("sites.find { it.id == '" + edgeSiteId + "' }.edgeHubId", is(hubId))
                .body("sites.find { it.id == '" + centralSiteId + "' }.edgeHubId", nullValue())
                .body("edgeHubs.name", hasItem(MARK + "berlin"));
    }

    @Test
    void anEdgeHubWithoutNetworks_isListedToo() {
        edgeHubs(true);
        QuarkusTransaction.requiringNew().run(() -> EdgeHub.createNew(MARK + "idle", 50.0, 8.0, null).persist());
        given().when().get("/api/v1/acl/atlas").then().statusCode(200)
                .body("edgeHubs.name", hasItem(MARK + "idle"));
    }

    @Test
    void siteKind_isInTheGraph_soTheDiagramCanMarkExternalNetworks() {
        String externalId = QuarkusTransaction.requiringNew().call(() -> {
            Site ext = Site.createNew(MARK + "partner", "203.0.113.0/28", null);
            ext.kind = Site.KIND_EXTERNAL;
            ext.persist();
            return ext.id;
        });
        given().when().get("/api/v1/acl/atlas").then().statusCode(200)
                .body("sites.find { it.id == '" + externalId + "' }.kind", is("external"))
                .body("sites.find { it.id == '" + centralSiteId + "' }.kind", is("internal"));
    }

    @Test
    void withEdgeHubsOff_everyNetworkIsCentral_andNoEdgeHubIsListed() {
        edgeHubs(false);
        given().when().get("/api/v1/acl/atlas").then().statusCode(200)
                .body("sites.find { it.id == '" + edgeSiteId + "' }.edgeHubId", nullValue())
                .body("edgeHubs", empty());
    }
}
