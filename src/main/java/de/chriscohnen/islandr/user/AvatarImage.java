package de.chriscohnen.islandr.user;

import jakarta.ws.rs.BadRequestException;

/**
 * Validation for uploaded avatars (issue #85) — type, size and pixel
 * dimensions, read straight out of the file header.
 *
 * <p>Islandr does not decode or re-encode the image. The runtime is a GraalVM
 * native image and ImageIO would drag AWT into it for one cosmetic feature, so
 * the downscale happens in the browser (a canvas draw before upload) and the
 * server verifies the result instead of trusting it: a hand-rolled request that
 * skips the browser step is rejected here, not stored.
 *
 * <p>Reading PNG and JPEG headers is a few lines each and needs no decoder.
 * Formats whose dimensions cannot be read this way are not accepted — an
 * avatar is not worth a parser.
 */
final class AvatarImage {

    /** A 256×256 avatar, with room for a 2× display, and a generous byte ceiling. */
    static final int MAX_EDGE = 512;
    static final int MAX_BYTES = 512 * 1024;

    record Meta(String contentType, int width, int height) {}

    private AvatarImage() {}

    /**
     * @throws BadRequestException naming what is wrong, in the admin's terms —
     *         this message is shown in the UI.
     */
    static Meta validate(byte[] bytes, String declaredContentType) {
        if (bytes == null || bytes.length == 0) throw new BadRequestException("no image data");
        if (bytes.length > MAX_BYTES) {
            throw new BadRequestException("image is larger than " + (MAX_BYTES / 1024) + " KB");
        }

        Meta m = readPng(bytes);
        if (m == null) m = readJpeg(bytes);
        if (m == null) {
            throw new BadRequestException("only PNG and JPEG images are accepted"
                    + (declaredContentType == null ? "" : " (got " + declaredContentType + ")"));
        }
        if (m.width() > MAX_EDGE || m.height() > MAX_EDGE) {
            throw new BadRequestException("image is larger than " + MAX_EDGE + "×" + MAX_EDGE
                    + " pixels (" + m.width() + "×" + m.height() + ")");
        }
        if (m.width() < 1 || m.height() < 1) throw new BadRequestException("image has no dimensions");
        return m;
    }

    /** PNG: 8-byte signature, then an IHDR chunk whose first 8 bytes are w/h. */
    private static Meta readPng(byte[] b) {
        byte[] sig = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
        if (b.length < 24) return null;
        for (int i = 0; i < sig.length; i++) if (b[i] != sig[i]) return null;
        if (b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') return null;
        return new Meta("image/png", int32(b, 16), int32(b, 20));
    }

    /**
     * JPEG: walk the marker segments until a start-of-frame (SOF0..SOF3,
     * SOF5..SOF7, SOF9..SOF11, SOF13..SOF15), which carries height then width.
     */
    private static Meta readJpeg(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return null;
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) return null;
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (len < 2) return null;
            boolean isSof = (marker >= 0xC0 && marker <= 0xCF)
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (isSof) {
                int height = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int width  = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                return new Meta("image/jpeg", width, height);
            }
            i += 2 + len;
        }
        return null;
    }

    private static int int32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
