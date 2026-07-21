package de.chriscohnen.islandr.acme;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal hand-rolled DER (ASN.1) encoding — the same technique and scope
 * {@code TlsService.wrapPkcs1RsaKeyAsPkcs8} already uses for PKCS1 import
 * (ADR-0019), extended with the handful of extra primitives a PKCS#10 CSR
 * needs (OBJECT IDENTIFIER, UTF8String, IA5String, BIT STRING, SET, and a
 * context-specific tag for the CSR "attributes" field). Not a general ASN.1
 * library — exactly the constructs this codebase's certificate handling uses,
 * nothing more.
 */
final class Der {

    private Der() {}

    static final int TAG_INTEGER = 0x02;
    static final int TAG_BIT_STRING = 0x03;
    static final int TAG_OCTET_STRING = 0x04;
    static final int TAG_OID = 0x06;
    static final int TAG_UTF8_STRING = 0x0c;
    static final int TAG_SEQUENCE = 0x30;
    static final int TAG_SET = 0x31;
    static final int TAG_IA5_STRING = 0x16;

    static byte[] tagged(int tag, byte[] content) {
        byte[] length = length(content.length);
        byte[] out = new byte[1 + length.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    static byte[] length(int len) {
        if (len < 0x80) return new byte[]{(byte) len};
        int byteCount = 1;
        int tmp = len;
        while ((tmp >>>= 8) != 0) byteCount++;
        byte[] out = new byte[byteCount + 1];
        out[0] = (byte) (0x80 | byteCount);
        for (int i = byteCount; i >= 1; i--) {
            out[i] = (byte) (len & 0xFF);
            len >>>= 8;
        }
        return out;
    }

    static byte[] sequence(byte[]... parts) {
        return tagged(TAG_SEQUENCE, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tagged(TAG_SET, concat(parts));
    }

    static byte[] integer(int value) {
        return tagged(TAG_INTEGER, new byte[]{(byte) value});
    }

    /** Signed big-endian INTEGER content, minimal-length with the leading-zero-byte
     *  rule DER requires whenever the high bit of the first byte would otherwise
     *  flip the sign (e.g. every raw ECDSA {@code r}/{@code s} component, which
     *  come out of {@link java.math.BigInteger#toByteArray()} needing exactly this). */
    static byte[] integer(byte[] unsignedMagnitudeBigEndian) {
        byte[] v = unsignedMagnitudeBigEndian;
        int off = 0;
        while (off < v.length - 1 && v[off] == 0) off++;
        boolean needsPad = (v[off] & 0x80) != 0;
        byte[] content = new byte[v.length - off + (needsPad ? 1 : 0)];
        System.arraycopy(v, off, content, needsPad ? 1 : 0, v.length - off);
        return tagged(TAG_INTEGER, content);
    }

    static byte[] utf8String(String s) {
        return tagged(TAG_UTF8_STRING, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] ia5String(String s) {
        return tagged(TAG_IA5_STRING, s.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] octetString(byte[] content) {
        return tagged(TAG_OCTET_STRING, content);
    }

    /** DER BIT STRING with zero unused bits — every use here wraps byte-aligned
     *  content (a signature or a JDK-supplied SubjectPublicKeyInfo key value). */
    static byte[] bitString(byte[] content) {
        byte[] withUnusedBitsPrefix = new byte[content.length + 1];
        withUnusedBitsPrefix[0] = 0x00;
        System.arraycopy(content, 0, withUnusedBitsPrefix, 1, content.length);
        return tagged(TAG_BIT_STRING, withUnusedBitsPrefix);
    }

    /** Context-specific constructed tag (the CSR "attributes" field is
     *  {@code [0] IMPLICIT SET OF Attribute} — tag 0xA0, content identical to
     *  what a SET's content would be). */
    static byte[] contextConstructed(int tagNumber, byte[]... parts) {
        return tagged(0xA0 | (tagNumber & 0x1F), concat(parts));
    }

    /** OBJECT IDENTIFIER from dotted-decimal form (e.g. "1.2.840.10045.4.3.2"). */
    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(first * 40 + second);
        for (int i = 2; i < parts.length; i++) {
            writeOidArc(body, Long.parseLong(parts[i]));
        }
        return tagged(TAG_OID, body.toByteArray());
    }

    private static void writeOidArc(ByteArrayOutputStream out, long arc) {
        // Base-128, most significant group first, continuation bit (0x80) set on
        // every group except the last.
        int bitsNeeded = 64 - Long.numberOfLeadingZeros(Math.max(arc, 1));
        int groups = Math.max(1, (bitsNeeded + 6) / 7);
        for (int g = groups - 1; g >= 0; g--) {
            int shift = g * 7;
            int chunk = (int) ((arc >>> shift) & 0x7F);
            out.write(g == 0 ? chunk : (chunk | 0x80));
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    static byte[] concatAll(List<byte[]> parts) {
        return concat(parts.toArray(new byte[0][]));
    }
}
