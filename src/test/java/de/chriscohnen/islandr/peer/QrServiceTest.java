package de.chriscohnen.islandr.peer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the AWT-free QR PNG encoder. Validation is done by parsing the PNG
 * bytes directly (no ImageIO/AWT), mirroring the constraint that broke production:
 * the native image has no AWT, so the encoder — and this test — must not depend on it.
 */
class QrServiceTest {

    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private final QrService qr = new QrService();

    @Test
    void encodesConfLengthsThatFailedInNativeImage() {
        // 306 and 366 are the exact text lengths that 500'd in the native image
        // ("failed to encode QR for text of length 306/366") before the AWT-free rewrite.
        for (int len : new int[]{306, 366}) {
            byte[] png = qr.toPng("[Interface]\n" + "k".repeat(len - 12), 320);

            assertThat(png).startsWith(PNG_SIGNATURE);
            assertThat(chunkType(png, 8)).isEqualTo("IHDR");

            int width = readInt(png, 16);
            int height = readInt(png, 20);
            assertThat(width).isGreaterThan(0);
            assertThat(height).isEqualTo(width);   // QR is square
            assertThat(png[24]).isEqualTo((byte) 8); // 8-bit depth
            assertThat(png[25]).isEqualTo((byte) 0); // grayscale color type

            // Inflate IDAT and confirm the raw size matches height * (1 filter byte + width).
            byte[] raw = inflateFirstIdat(png);
            assertThat(raw).hasSize(height * (1 + width));
        }
    }

    @Test
    void toDataUrlIsBase64Png() {
        String url = qr.toDataUrl("wireguard-conf-" + "y".repeat(350));
        assertThat(url).startsWith("data:image/png;base64,");
        byte[] png = Base64.getDecoder().decode(url.substring("data:image/png;base64,".length()));
        assertThat(png).startsWith(PNG_SIGNATURE);
    }

    // --- tiny PNG reader (no AWT) ------------------------------------------------

    private static String chunkType(byte[] png, int lengthFieldOffset) {
        return new String(png, lengthFieldOffset + 4, 4, StandardCharsets.US_ASCII);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** Find the first IDAT chunk and inflate its zlib stream. */
    private static byte[] inflateFirstIdat(byte[] png) {
        int pos = 8; // skip signature
        while (pos + 8 <= png.length) {
            int len = readInt(png, pos);
            String type = chunkType(png, pos);
            int dataStart = pos + 8;
            if ("IDAT".equals(type)) {
                Inflater inflater = new Inflater();
                inflater.setInput(png, dataStart, len);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                try {
                    while (!inflater.finished()) {
                        int n = inflater.inflate(buf);
                        if (n == 0) break;
                        out.write(buf, 0, n);
                    }
                } catch (DataFormatException e) {
                    throw new AssertionError("IDAT is not valid zlib data", e);
                } finally {
                    inflater.end();
                }
                return out.toByteArray();
            }
            pos = dataStart + len + 4; // data + CRC
        }
        throw new AssertionError("no IDAT chunk found");
    }
}
