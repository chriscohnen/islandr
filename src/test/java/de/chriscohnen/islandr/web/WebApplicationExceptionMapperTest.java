package de.chriscohnen.islandr.web;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * rest-error-body-empty: a thrown {@code WebApplicationException} with only a
 * message (never an explicit {@code Response}) must put that message in the
 * HTTP response body — plain JAX-RS otherwise stores it only in
 * {@code getMessage()}, never in the entity, leaving every frontend
 * {@code await res.text()} error banner blank. Found while verifying
 * peer-self-share, confirmed pre-existing on {@code PeerResource}'s own
 * {@code NotFoundException} throws.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class WebApplicationExceptionMapperTest {

    @Test
    void notFoundExceptionWithOnlyAMessage_putsTheMessageInTheBody() {
        given().when().get("/api/v1/peers/does-not-exist")
                .then().statusCode(404)
                .body(containsString("peer not found"));
    }

    @Test
    void aResponseThatAlreadyCarriesAnEntity_isLeftUnchanged() {
        // Several places (ResourceService, PortScanResource, DiscoveryResource,
        // NetworkDiagnosticsService) throw `new WebApplicationException(Response
        // .status(...).entity(message).build())` deliberately — the mapper must
        // pass those straight through, never double-wrap or replace the body.
        WebApplicationException ex = new WebApplicationException(
                Response.status(Response.Status.CONFLICT).entity("a resource named 'x' already exists").build());

        Response mapped = new WebApplicationExceptionMapper().toResponse(ex);

        assertThat(mapped.getStatus()).isEqualTo(409);
        assertThat(mapped.getEntity()).isEqualTo("a resource named 'x' already exists");
    }
}
