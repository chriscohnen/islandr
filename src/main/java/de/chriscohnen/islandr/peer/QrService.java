package de.chriscohnen.islandr.peer;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

@ApplicationScoped
public class QrService {

    private static final int DEFAULT_SIZE = 320;
    private static final int MARGIN = 1;

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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new RuntimeException("failed to encode QR for text of length " + text.length(), e);
        }
    }
}
