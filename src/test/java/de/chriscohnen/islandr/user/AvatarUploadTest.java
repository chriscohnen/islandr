package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.auth.AdminSessionExtension;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.zip.CRC32;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #85: a local account could only ever get a face by switching Gravatar
 * on — the browser fetching from gravatar.com, the one outbound call the
 * product is otherwise built not to make. The write path was simply missing;
 * the columns, the resolution chain and the GET have been there all along.
 *
 * <p>The server does not decode images (no AWT in a native image), so what it
 * owes is header validation: these tests pin that a too-large picture is
 * refused rather than stored, and that a non-image is not taken on the word of
 * its content-type header.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class AvatarUploadTest {

    @Transactional
    String seedUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User u = User.createNew("Avatar " + suffix, "avatar-" + suffix + "@firma.de");
        u.persist();
        return u.id;
    }

    @Transactional
    void deleteUser(String id) {
        User.deleteById(id);
    }

    @Test
    void uploadedPng_isStoredAndServedBack() {
        String id = seedUser();
        try {
            byte[] png = png(64, 64);
            given().contentType("image/png").body(png)
                    .when().put("/api/v1/users/" + id + "/avatar")
                    .then().statusCode(204);

            byte[] served = given().when().get("/api/v1/users/" + id + "/avatar")
                    .then().statusCode(200)
                    .extract().asByteArray();
            assertThat(served).isEqualTo(png);

            // Removing it restores the ordinary fallback chain, which for a
            // local account with Gravatar off means no image at all.
            given().when().delete("/api/v1/users/" + id + "/avatar").then().statusCode(204);
            given().when().get("/api/v1/users/" + id + "/avatar").then().statusCode(404);
        } finally {
            deleteUser(id);
        }
    }

    @Test
    void anImageLargerThanTheCeiling_isRefused() {
        String id = seedUser();
        try {
            given().contentType("image/png").body(png(1024, 1024))
                    .when().put("/api/v1/users/" + id + "/avatar")
                    .then().statusCode(400);
            given().when().get("/api/v1/users/" + id + "/avatar").then().statusCode(404);
        } finally {
            deleteUser(id);
        }
    }

    /** The content-type header is the uploader's claim, not evidence. */
    @Test
    void aNonImageLabelledAsPng_isRefused() {
        String id = seedUser();
        try {
            given().contentType("image/png").body("#!/bin/sh\nrm -rf /\n".getBytes())
                    .when().put("/api/v1/users/" + id + "/avatar")
                    .then().statusCode(400);
        } finally {
            deleteUser(id);
        }
    }

    /** A minimal but structurally valid PNG: signature + IHDR + CRC. */
    private static byte[] png(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });
        byte[] ihdr = new byte[17];
        ihdr[0] = 'I'; ihdr[1] = 'H'; ihdr[2] = 'D'; ihdr[3] = 'R';
        writeInt(ihdr, 4, width);
        writeInt(ihdr, 8, height);
        ihdr[12] = 8;   // bit depth
        ihdr[13] = 6;   // colour type: RGBA
        byte[] len = new byte[4];
        writeInt(len, 0, 13);
        out.writeBytes(len);
        out.writeBytes(ihdr);
        CRC32 crc = new CRC32();
        crc.update(ihdr, 0, 17);
        byte[] c = new byte[4];
        writeInt(c, 0, (int) crc.getValue());
        out.writeBytes(c);
        return out.toByteArray();
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
