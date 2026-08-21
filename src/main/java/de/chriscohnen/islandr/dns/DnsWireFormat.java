package de.chriscohnen.islandr.dns;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal RFC 1035 message encode/decode — hand-rolled, no library, per
 * <a href="../../../../../../../docs/adr/0023-resource-dns-resolver-hand-rolled.md">ADR-0023</a>.
 *
 * <p>Scope is deliberately narrow: parses a single-question query (no
 * compression on the wire in — a fresh stub-resolver query is never
 * compressed) and builds a single-answer A/AAAA response or an error
 * response with no answers. A real peer's forwarded (non-managed-zone) query
 * is relayed by {@link DnsResolverService#forward} completely unparsed on
 * both legs — simpler, and narrows that hot path's exposure to attacker-
 * reachable input to the query side only. {@link #buildQuery} and
 * {@link #parseFirstAnswerAddress} are the one deliberate exception: a
 * synthetic query and response parse used solely by the admin-triggered,
 * on-demand lookup preview ({@link DnsResolverService#queryUpstreamForPreview}),
 * never by anything a peer can reach.
 */
final class DnsWireFormat {

    static final int TYPE_A = 1;
    static final int TYPE_AAAA = 28;
    static final int TYPE_PTR = 12;
    static final int CLASS_IN = 1;

    static final int RCODE_NO_ERROR = 0;
    static final int RCODE_NXDOMAIN = 3;

    private DnsWireFormat() {}

    /** A parsed single-question query. {@code questionEnd} is the byte offset
     *  right after QCLASS — where the (absent, for a fresh query) answer
     *  section would begin. */
    record Query(int id, boolean recursionDesired, String name, int qtype, int qclass, int questionEnd) {}

    /**
     * @throws IllegalArgumentException on anything this narrow parser doesn't
     *         support (message too short, QDCOUNT != 1, a response instead of
     *         a query, a compressed name in the question, truncated data).
     *         Callers should forward such queries upstream rather than treat
     *         them as errors — a resolver this narrow will see legitimate
     *         queries it doesn't need to understand (e.g. multi-question
     *         messages), and forwarding is always safe.
     */
    static Query parseQuery(byte[] data, int length) {
        if (length < 12) throw new IllegalArgumentException("message shorter than a DNS header");
        int flags = u16(data, 2);
        if (((flags >> 15) & 1) != 0) throw new IllegalArgumentException("QR bit set — not a query");
        int qdcount = u16(data, 4);
        if (qdcount != 1) throw new IllegalArgumentException("only single-question queries are supported, got " + qdcount);

        int id = u16(data, 0);
        boolean rd = (flags & 0x0100) != 0;

        int pos = 12;
        StringBuilder name = new StringBuilder();
        while (true) {
            if (pos >= length) throw new IllegalArgumentException("truncated QNAME");
            int labelLen = data[pos] & 0xff;
            if (labelLen == 0) { pos++; break; }
            if ((labelLen & 0xC0) != 0) throw new IllegalArgumentException("compressed name in question, unsupported");
            pos++;
            if (pos + labelLen > length) throw new IllegalArgumentException("truncated label");
            if (name.length() > 0) name.append('.');
            name.append(new String(data, pos, labelLen, StandardCharsets.US_ASCII));
            pos += labelLen;
        }
        if (pos + 4 > length) throw new IllegalArgumentException("truncated QTYPE/QCLASS");
        int qtype = u16(data, pos);
        int qclass = u16(data, pos + 2);
        pos += 4;
        return new Query(id, rd, name.toString(), qtype, qclass, pos);
    }

    /** Authoritative answer: echoes the question, one A/AAAA record pointing
     *  at {@code addressBytes} (4 bytes for A, 16 for AAAA). */
    static byte[] buildAnswer(byte[] query, Query parsed, byte[] addressBytes, int ttlSeconds) {
        int rtype = addressBytes.length == 16 ? TYPE_AAAA : TYPE_A;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, parsed.id());
        writeU16(out, header(parsed.recursionDesired(), true, RCODE_NO_ERROR));
        writeU16(out, 1); // QDCOUNT
        writeU16(out, 1); // ANCOUNT
        writeU16(out, 0); // NSCOUNT
        writeU16(out, 0); // ARCOUNT
        out.write(query, 12, parsed.questionEnd() - 12); // echo question verbatim
        writeU16(out, 0xC00C); // NAME: pointer to the question name at offset 12
        writeU16(out, rtype);
        writeU16(out, CLASS_IN);
        writeU32(out, ttlSeconds);
        writeU16(out, addressBytes.length);
        out.write(addressBytes, 0, addressBytes.length);
        return out.toByteArray();
    }

    /** Builds a real answer when the queried type matches the address family
     *  we actually have, otherwise NODATA (NOERROR, zero answers) — never a
     *  record whose TYPE contradicts the echoed question's QTYPE. Every
     *  managed Resource only ever carries one literal (almost always IPv4),
     *  but a real stub resolver (glibc getaddrinfo/AF_UNSPEC — what `ping`
     *  uses without -4/-6) queries AAAA and A independently. Answering the
     *  AAAA leg with a 4-byte address mislabeled TYPE=A used to produce a
     *  wire-malformed response that several resolvers silently treat as "no
     *  usable data" without ever falling back to the (perfectly fine) A
     *  query — this is the fix. */
    static byte[] buildAnswerOrNoData(byte[] query, Query parsed, byte[] addressBytes, int ttlSeconds) {
        boolean queriedFamilyMatches = (parsed.qtype() == TYPE_AAAA) == (addressBytes.length == 16);
        return queriedFamilyMatches ? buildAnswer(query, parsed, addressBytes, ttlSeconds) : buildNoData(query, parsed);
    }

    /** NODATA: the name exists (so NOERROR, not NXDOMAIN) but there's nothing
     *  of the queried type — e.g. an AAAA query against an IPv4-only
     *  resource. Distinct from {@link #buildError}'s NXDOMAIN, which means
     *  the name itself doesn't resolve at all. */
    static byte[] buildNoData(byte[] query, Query parsed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, parsed.id());
        writeU16(out, header(parsed.recursionDesired(), true, RCODE_NO_ERROR));
        writeU16(out, 1); // QDCOUNT
        writeU16(out, 0); // ANCOUNT
        writeU16(out, 0); // NSCOUNT
        writeU16(out, 0); // ARCOUNT
        out.write(query, 12, parsed.questionEnd() - 12);
        return out.toByteArray();
    }

    /** Error response (no answers) — used for NXDOMAIN, both "no such resource"
     *  and "resource exists but this peer has no grant" (ADR-0023: those two
     *  cases are intentionally indistinguishable on the wire). */
    static byte[] buildError(byte[] query, Query parsed, int rcode) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, parsed.id());
        writeU16(out, header(parsed.recursionDesired(), true, rcode));
        writeU16(out, 1); // QDCOUNT
        writeU16(out, 0); // ANCOUNT
        writeU16(out, 0); // NSCOUNT
        writeU16(out, 0); // ARCOUNT
        out.write(query, 12, parsed.questionEnd() - 12);
        return out.toByteArray();
    }

    /** Builds a synthetic query — used only by the System → DNS page's admin
     *  lookup preview to ask a real upstream resolver directly for a name
     *  outside the managed zone (so the admin sees an actual answer instead
     *  of just "would be forwarded"), never by the resolver's own listening
     *  path (which only ever relays a real client's own query bytes). */
    static byte[] buildQuery(int id, String name, int qtype) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, id);
        writeU16(out, 0x0100); // RD=1, standard query, everything else 0
        writeU16(out, 1); // QDCOUNT
        writeU16(out, 0); // ANCOUNT
        writeU16(out, 0); // NSCOUNT
        writeU16(out, 0); // ARCOUNT
        for (String label : name.split("\\.")) {
            if (label.isEmpty()) continue;
            byte[] l = label.getBytes(StandardCharsets.US_ASCII);
            out.write(Math.min(l.length, 63));
            out.write(l, 0, Math.min(l.length, 63));
        }
        out.write(0); // root label
        writeU16(out, qtype);
        writeU16(out, CLASS_IN);
        return out.toByteArray();
    }

    /** Extracts the first A/AAAA answer address from a raw response — used
     *  only by the admin lookup preview's live-upstream-query feature
     *  (DnsResolverService#queryUpstreamForPreview), never by the resolver's
     *  own forward path (DnsResolverService#forward relays a forwarded
     *  response to a real peer completely unparsed, deliberately, per this
     *  class's own doc comment above). Returns null on anything malformed
     *  rather than throwing — this is a best-effort admin convenience over a
     *  low-volume, on-demand action, not the peer-facing hot path, but it's
     *  still parsing bytes from an external network actor (the upstream
     *  resolver), so it must fail safe, never fail loud. */
    static String parseFirstAnswerAddress(byte[] data) {
        try {
            if (data.length < 12) return null;
            int qdcount = u16(data, 4);
            int ancount = u16(data, 6);
            if (ancount < 1) return null;
            int pos = 12;
            for (int i = 0; i < qdcount; i++) {
                pos = skipName(data, pos);
                pos += 4; // QTYPE + QCLASS
            }
            for (int i = 0; i < ancount; i++) {
                pos = skipName(data, pos);
                int type = u16(data, pos); pos += 2;
                pos += 2; // CLASS
                pos += 4; // TTL
                int rdlength = u16(data, pos); pos += 2;
                if (type == TYPE_A && rdlength == 4) {
                    return (data[pos] & 0xff) + "." + (data[pos + 1] & 0xff) + "."
                            + (data[pos + 2] & 0xff) + "." + (data[pos + 3] & 0xff);
                }
                if (type == TYPE_AAAA && rdlength == 16) {
                    try {
                        return InetAddress.getByAddress(Arrays.copyOfRange(data, pos, pos + 16)).getHostAddress();
                    } catch (UnknownHostException e) {
                        return null; // wrong-length address array — can't happen given the check above, fail safe anyway
                    }
                }
                pos += rdlength; // some other record type (e.g. CNAME) — skip and keep looking
            }
            return null;
        } catch (RuntimeException e) {
            // Any bounds/format surprise in attacker-reachable bytes — no
            // answer, not a crash.
            return null;
        }
    }

    /** Extracts the first PTR answer's target domain name from a raw
     *  response — used only by {@link PtrLookup}, the discovery-scan
     *  reverse-DNS lookup against a site's configured local DNS server,
     *  never by the resolver's own forward path. Same fail-safe posture as
     *  {@link #parseFirstAnswerAddress}: malformed/attacker-reachable bytes
     *  return null, never throw. */
    static String parseFirstPtrName(byte[] data) {
        try {
            if (data.length < 12) return null;
            int qdcount = u16(data, 4);
            int ancount = u16(data, 6);
            if (ancount < 1) return null;
            int pos = 12;
            for (int i = 0; i < qdcount; i++) {
                pos = skipName(data, pos);
                pos += 4; // QTYPE + QCLASS
            }
            for (int i = 0; i < ancount; i++) {
                pos = skipName(data, pos);
                int type = u16(data, pos); pos += 2;
                pos += 2; // CLASS
                pos += 4; // TTL
                int rdlength = u16(data, pos); pos += 2;
                if (type == TYPE_PTR) {
                    return readName(data, pos);
                }
                pos += rdlength; // some other record type — skip and keep looking
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads a (possibly compressed) NAME field starting at {@code pos} and
     *  returns its dotted string form, following at most one compression
     *  pointer — sufficient for the simple single-answer responses a home
     *  router's DNS server sends; a longer pointer chain is treated as
     *  malformed, matching this class's fail-safe-return-null posture. */
    private static String readName(byte[] data, int pos) {
        StringBuilder name = new StringBuilder();
        boolean jumped = false;
        int guard = 0;
        while (guard++ < 128) {
            int len = data[pos] & 0xff;
            if (len == 0) break;
            if ((len & 0xC0) == 0xC0) {
                if (jumped) return null; // more than one pointer — treat as malformed
                pos = ((len & 0x3F) << 8) | (data[pos + 1] & 0xff);
                jumped = true;
                continue;
            }
            pos++;
            if (name.length() > 0) name.append('.');
            name.append(new String(data, pos, len, StandardCharsets.US_ASCII));
            pos += len;
        }
        return name.length() > 0 ? name.toString() : null;
    }

    /** Advances past one (possibly compressed) NAME field, returning the
     *  offset immediately after it. Compression pointers are always exactly
     *  2 bytes and always terminate the name at that point (RFC 1035 §4.1.4). */
    private static int skipName(byte[] data, int pos) {
        while (true) {
            int len = data[pos] & 0xff;
            if (len == 0) return pos + 1;
            if ((len & 0xC0) == 0xC0) return pos + 2; // compression pointer
            pos += 1 + len;
        }
    }

    private static int header(boolean rd, boolean authoritative, int rcode) {
        int flags = 0x8000; // QR=1
        if (authoritative) flags |= 0x0400; // AA
        if (rd) flags |= 0x0100; // echo RD
        // RA — every query this server sees gets a real answer one way or
        // another: authoritative in-zone, or forwarded to a real upstream
        // out-of-zone (DnsResolverService#forward). It never actually lacks
        // recursion from the querying client's point of view, so claiming
        // RA=0 was simply wrong, not just conservative — and cost real,
        // correct answers: some stub resolvers (macOS/BSD nslookup among
        // them) distrust an RA=0 response and silently retry the next
        // configured server even when this one's answer was valid.
        flags |= 0x0080;
        return flags | (rcode & 0xF);
    }

    private static int u16(byte[] d, int off) {
        return ((d[off] & 0xff) << 8) | (d[off + 1] & 0xff);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >>> 24) & 0xff));
        out.write((int) ((v >>> 16) & 0xff));
        out.write((int) ((v >>> 8) & 0xff));
        out.write((int) (v & 0xff));
    }
}
