package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class UserResourceTest {

    @Test
    void createListGetDelete_roundtrip() {
        // create
        String id = given()
                .contentType("application/json")
                .body("""
                        { "name": "Felix Sysadmin", "email": "felix@example.com" }
                        """)
                .when().post("/api/v1/users")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("name", equalTo("Felix Sysadmin"))
                .body("email", equalTo("felix@example.com"))
                .body("enabled", is(true))
                .extract().path("id");

        // get
        given().when().get("/api/v1/users/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("email", equalTo("felix@example.com"));

        // list
        given().when().get("/api/v1/users")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));

        // delete
        given().when().delete("/api/v1/users/" + id)
                .then().statusCode(204);

        // gone
        given().when().get("/api/v1/users/" + id)
                .then().statusCode(404);
    }

    @Test
    void create_rejectsInvalidEmail() {
        given()
                .contentType("application/json")
                .body("""
                        { "name": "Bad", "email": "not-an-email" }
                        """)
                .when().post("/api/v1/users")
                .then().statusCode(400);
    }

    @Test
    void create_rejectsBlankName() {
        given()
                .contentType("application/json")
                .body("""
                        { "name": "", "email": "ok@example.com" }
                        """)
                .when().post("/api/v1/users")
                .then().statusCode(400);
    }

    @Test
    void get_missingReturns404() {
        given().when().get("/api/v1/users/does-not-exist")
                .then().statusCode(404);
    }
}
