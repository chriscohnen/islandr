package de.chriscohnen.islandr.peer;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Renders a WireGuard config to a QR-code PNG.
 *
 * <p>The PNG is encoded with a small pure-Java writer ({@link java.util.zip.Deflater} +
 * a hand-built grayscale PNG) instead of {@code MatrixToImageWriter} / {@code ImageIO}.
 * The latter pulls in AWT, which is unavailable in the GraalVM native image we ship
 * ({@code FROM scratch}, no AWT/ImageIO providers) — there it failed at runtime with
 * "failed to encode QR" the first time a peer was created. Staying on {@code zxing-core}
 * + {@code java.util.zip} keeps QR generation working in both JVM and native builds.
 */
@ApplicationScoped
public class QrService {

    private static final int DEFAULT_SIZE = 320;
    private static final int MARGIN = 1;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    /** Render the given text to a PNG and return a {@code data:image/png;base64,…} URL. */
    public String toDataUrl(String text) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(toPng(text, DEFAULT_SIZE));
    }

    public byte[] toPng(String text, int size) {
        Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.MARGIN, MARGIN,
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET, "UTF-8"
        );
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            return matrixToPng(matrix);
        } catch (WriterException e) {
            throw new RuntimeException("failed to encode QR for text of length " + text.length(), e);
        }
    }

    /**
     * Encode a black/white {@link BitMatrix} as an 8-bit grayscale PNG.
     * Black module → 0x00, white → 0xFF. No AWT.
     */
    private static byte[] matrixToPng(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();

        // Raw scanlines: each row is one filter byte (0 = none) + `width` grayscale bytes.
        byte[] raw = new byte[height * (1 + width)];
        int idx = 0;
        for (int y = 0; y < height; y++) {
            raw[idx++] = 0; // filter type: none
            for (int x = 0; x < width; x++) {
                raw[idx++] = matrix.get(x, y) ? (byte) 0x00 : (byte) 0xFF;
            }
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(PNG_SIGNATURE);

        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, width);
        writeInt(ihdr, height);
        ihdr.write(8); // bit depth
        ihdr.write(0); // color type: grayscale
        ihdr.write(0); // compression: deflate
        ihdr.write(0); // filter: adaptive
        ihdr.write(0); // interlace: none
        writeChunk(png, "IHDR", ihdr.toByteArray());

        writeChunk(png, "IDAT", deflate(raw));
        writeChunk(png, "IEND", new byte[0]);

        return png.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(out, data.length);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
