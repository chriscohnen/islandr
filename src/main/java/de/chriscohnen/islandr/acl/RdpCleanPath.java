package de.chriscohnen.islandr.acl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal ASN.1 DER encoder/decoder for the RDCleanPath protocol used by the
 * IronRDP WASM client to negotiate a WebSocket-to-RDP proxy session.
 *
 * Protocol flow (see electerm/ironrdp-wasm example/lib/rdp-proxy.js):
 *   1. Client sends first binary WebSocket message: DER-encoded RDCleanPath Request
 *   2. Proxy: parse request → TCP connect → X.224 exchange → TLS → extract certs
 *   3. Proxy sends DER-encoded RDCleanPath Response
 *   4. Bidirectional relay: WebSocket binary ↔ TLS socket
 */
final class RdpCleanPath {

    static final int VERSION = 3390; // RDP port + 1

    // ASN.1 DER universal tags
    private static final int TAG_SEQUENCE = 0x30;
    private static final int TAG_INTEGER  = 0x02;
    private static final int TAG_OCTET    = 0x04;
    private static final int TAG_UTF8     = 0x0c;

    record Request(String destination, String proxyAuth, byte[] x224ConnectionRequest) {}

    // ── Public API ───────────────────────────────────────────────────────────

    static Request parseRequest(byte[] data) {
        if ((data[0] & 0xFF) != TAG_SEQUENCE) {
            throw new IllegalArgumentException(
                    "Expected SEQUENCE (0x30), got 0x" + Integer.toHexString(data[0] & 0xFF));
        }
        // Skip outer SEQUENCE tag + length
        int[] ld = decodeLen(data, 1);
        int off = 1 + ld[1];

        String destination = null;
        String proxyAuth   = null;
        byte[] x224        = null;

        while (off < data.length) {
            int tag = data[off] & 0xFF;
            int[] cl = decodeLen(data, off + 1);
            int hdr = 1 + cl[1];
            byte[] value = Arrays.copyOfRange(data, off + hdr, off + hdr + cl[0]);
            off += hdr + cl[0];

            switch (tag & 0x1F) { // strip class bits to get context tag number
                case 2 -> destination = innerUtf8(value);
                case 3 -> proxyAuth   = innerUtf8(value);
                case 6 -> x224        = innerOctet(value);
                default -> { /* version, preconnection_blob, etc. — skip */ }
            }
        }
        if (destination == null || x224 == null) {
            throw new IllegalArgumentException("Missing destination or x224_connection_pdu");
        }
        return new Request(destination, proxyAuth, x224);
    }

    static byte[] buildResponse(String serverAddr, byte[] x224Response, byte[][] certChain) {
        // [7] EXPLICIT SEQUENCE OF OCTET STRING
        byte[][] certOctets = Arrays.stream(certChain).map(RdpCleanPath::octet).toArray(byte[][]::new);
        byte[] certSeq = wrap(TAG_SEQUENCE, concat(certOctets));

        return wrap(TAG_SEQUENCE, concat(
                ctx(0, integer(VERSION)),
                ctx(6, octet(x224Response)),
                ctx(7, certSeq),
                ctx(9, utf8(serverAddr))
        ));
    }

    static byte[] buildError(int errorCode, int httpStatus) {
        List<byte[]> errFields = new ArrayList<>();
        errFields.add(ctx(0, integer(errorCode)));
        if (httpStatus > 0) errFields.add(ctx(1, integer(httpStatus)));
        byte[] errSeq = wrap(TAG_SEQUENCE, concat(errFields.toArray(byte[][]::new)));

        return wrap(TAG_SEQUENCE, concat(
                ctx(0, integer(VERSION)),
                ctx(1, errSeq)
        ));
    }

    // ── Inner TLV readers ────────────────────────────────────────────────────

    // EXPLICIT context tag wraps exactly one inner TLV:
    //   value = [inner_tag, inner_len..., inner_content...]

    private static String innerUtf8(byte[] value) {
        int[] il = decodeLen(value, 1);           // length at value[1], after inner tag
        return new String(value, 1 + il[1], il[0], StandardCharsets.UTF_8);
    }

    private static byte[] innerOctet(byte[] value) {
        int[] il = decodeLen(value, 1);
        return Arrays.copyOfRange(value, 1 + il[1], 1 + il[1] + il[0]);
    }

    // ── DER encoding ─────────────────────────────────────────────────────────

    private static byte[] encodeLen(int len) {
        if (len < 0x80) return new byte[]{(byte) len};
        List<Byte> bytes = new ArrayList<>();
        while (len > 0) { bytes.add(0, (byte) (len & 0xFF)); len >>= 8; }
        byte[] out = new byte[bytes.size() + 1];
        out[0] = (byte) (0x80 | bytes.size());
        for (int i = 0; i < bytes.size(); i++) out[1 + i] = bytes.get(i);
        return out;
    }

    private static byte[] wrap(int tag, byte[] content) {
        byte[] lenBytes = encodeLen(content.length);
        byte[] out = new byte[1 + lenBytes.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.length);
        System.arraycopy(content, 0, out, 1 + lenBytes.length, content.length);
        return out;
    }

    private static byte[] integer(int value) {
        if (value == 0) return wrap(TAG_INTEGER, new byte[]{0});
        List<Byte> bytes = new ArrayList<>();
        int v = value;
        while (v > 0) { bytes.add(0, (byte) (v & 0xFF)); v >>= 8; }
        if ((bytes.get(0) & 0x80) != 0) bytes.add(0, (byte) 0); // ensure positive
        byte[] b = new byte[bytes.size()];
        for (int i = 0; i < b.length; i++) b[i] = bytes.get(i);
        return wrap(TAG_INTEGER, b);
    }

    private static byte[] utf8(String s) {
        return wrap(TAG_UTF8, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] octet(byte[] data) {
        return wrap(TAG_OCTET, data);
    }

    private static byte[] ctx(int tagNum, byte[] content) {
        return wrap(0xa0 + tagNum, content);
    }

    // ── DER decoding ─────────────────────────────────────────────────────────

    private static int[] decodeLen(byte[] buf, int off) {
        // Returns [decoded_length, bytes_consumed_for_length_field]
        int first = buf[off] & 0xFF;
        if (first < 0x80) return new int[]{first, 1};
        int n = first & 0x7F;
        int len = 0;
        for (int i = 0; i < n; i++) len = (len << 8) | (buf[off + 1 + i] & 0xFF);
        return new int[]{len, 1 + n};
    }

    // ── Byte array concat ─────────────────────────────────────────────────────

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, off, p.length); off += p.length; }
        return out;
    }

    private RdpCleanPath() {}
}
