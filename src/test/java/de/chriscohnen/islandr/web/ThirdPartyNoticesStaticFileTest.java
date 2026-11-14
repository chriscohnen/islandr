package de.chriscohnen.islandr.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * ship-licence-notices: the third-party licences and NOTICE texts of the
 * libraries compiled into Islandr are served as a plain static file, the one
 * Settings links to. Public like index.html — they are published licence
 * texts, not configuration.
 */
@QuarkusTest
class ThirdPartyNoticesStaticFileTest {

    @Test
    void isServed_withoutAuthentication_asPlainText() {
        given().when().get("/licenses/THIRD-PARTY-NOTICES.txt")
                .then().statusCode(200)
                .contentType(startsWith("text/plain"))
                .body(containsString("Third-party software in Islandr"))
                .body(containsString("Apache License, Version 2.0"))
                .body(containsString("io.quarkus:quarkus-core"));
    }
}
