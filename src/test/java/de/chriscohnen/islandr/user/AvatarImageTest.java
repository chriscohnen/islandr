package de.chriscohnen.islandr.user;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Header parsing for uploaded avatars (issue #85). Islandr has no image
 * decoder — AWT would be dragged into the native image for one cosmetic
 * feature — so dimensions are read out of the PNG and JPEG headers directly,
 * and these tests are the only thing standing between that code and a stored
 * file nobody checked.
 */
class AvatarImageTest {

    @Test
    void readsJpegDimensionsFromTheStartOfFrame() {
        AvatarImage.Meta m = AvatarImage.validate(jpeg(200, 120), "image/jpeg");
        assertThat(m.contentType()).isEqualTo("image/jpeg");
        assertThat(m.width()).isEqualTo(200);
        assertThat(m.height()).isEqualTo(120);
    }

    /** A JPEG carrying application segments before the frame still parses. */
    @Test
    void skipsApplicationSegmentsBeforeTheFrame() {
        AvatarImage.Meta m = AvatarImage.validate(jpegWithExif(64, 64), "image/jpeg");
        assertThat(m.width()).isEqualTo(64);
    }

    @Test
    void refusesAnOversizedJpeg() {
        assertThatThrownBy(() -> AvatarImage.validate(jpeg(4000, 3000), "image/jpeg"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("4000");
    }

    @Test
    void refusesSomethingThatIsNotAnImage() {
        assertThatThrownBy(() -> AvatarImage.validate("not an image".getBytes(), "image/png"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("PNG and JPEG");
    }

    @Test
    void refusesAnEmptyBody() {
        assertThatThrownBy(() -> AvatarImage.validate(new byte[0], "image/png"))
                .isInstanceOf(BadRequestException.class);
    }

    private static byte[] jpeg(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { (byte) 0xFF, (byte) 0xD8 });   // SOI
        out.writeBytes(sof0(width, height));
        return out.toByteArray();
    }

    private static byte[] jpegWithExif(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { (byte) 0xFF, (byte) 0xD8 });
        // APP1 segment with 10 bytes of payload, skipped by length.
        out.writeBytes(new byte[] { (byte) 0xFF, (byte) 0xE1, 0x00, 0x0C });
        out.writeBytes(new byte[10]);
        out.writeBytes(sof0(width, height));
        return out.toByteArray();
    }

    private static byte[] sof0(int width, int height) {
        return new byte[] {
                (byte) 0xFF, (byte) 0xC0,          // SOF0
                0x00, 0x11,                        // segment length (17)
                0x08,                              // sample precision
                (byte) (height >> 8), (byte) height,
                (byte) (width >> 8), (byte) width,
                0x03,                              // component count
                0, 0, 0, 0, 0, 0, 0, 0, 0,
        };
    }
}
