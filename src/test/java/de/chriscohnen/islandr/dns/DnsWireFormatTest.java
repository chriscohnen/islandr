package de.chriscohnen.islandr.dns;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure wire-format round-trip checks for the hand-rolled RFC 1035 parser/
 * builder behind the DNS resolver (ADR-0023) — no DB, no sockets.
 */
class DnsWireFormatTest {

    @Test
    void parseQuery_extractsNameAndType() throws Exception {
        byte[] q = rawQuery(0x1234, true, "fileserver.homeoffice.islandr.internal", DnsWireFormat.TYPE_A);
        DnsWireFormat.Query parsed = DnsWireFormat.parseQuery(q, q.length);

        assertThat(parsed.id()).isEqualTo(0x1234);
        assertThat(parsed.recursionDesired()).isTrue();
        assertThat(parsed.name()).isEqualTo("fileserver.homeoffice.islandr.internal");
        assertThat(parsed.qtype()).isEqualTo(DnsWireFormat.TYPE_A);
        assertThat(parsed.qclass()).isEqualTo(DnsWireFormat.CLASS_IN);
        assertThat(parsed.questionEnd()).isEqualTo(q.length);
    }

    @Test
    void parseQuery_rejectsMultiQuestionMessages() throws Exception {
        byte[] q = rawQuery(1, false, "a.b", DnsWireFormat.TYPE_A);
        q[5] = 2; // lie about QDCOUNT (real single-question messages never do this)

        assertThatThrownBy(() -> DnsWireFormat.parseQuery(q, q.length))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseQuery_rejectsResponses() throws Exception {
        byte[] q = rawQuery(1, false, "a.b", DnsWireFormat.TYPE_A);
        q[2] = (byte) (q[2] | 0x80); // set QR — this is a malformed "query" in reality

        assertThatThrownBy(() -> DnsWireFormat.parseQuery(q, q.length))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseQuery_rejectsTruncatedMessages() {
        assertThatThrownBy(() -> DnsWireFormat.parseQuery(new byte[]{0, 1, 2}, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildAnswer_echoesQuestionAndCarriesAddress() throws Exception {
        byte[] q = rawQuery(42, true, "fileserver.homeoffice.islandr.internal", DnsWireFormat.TYPE_A);
        DnsWireFormat.Query parsed = DnsWireFormat.parseQuery(q, q.length);
        byte[] ip = InetAddress.getByName("10.8.0.42").getAddress();

        byte[] resp = DnsWireFormat.buildAnswer(q, parsed, ip, 30);

        assertThat(u16(resp, 0)).isEqualTo(42); // ID echoed
        int flags = u16(resp, 2);
        assertThat((flags >> 15) & 1).isEqualTo(1); // QR
        assertThat((flags >> 10) & 1).isEqualTo(1); // AA
        assertThat((flags >> 8) & 1).isEqualTo(1);  // RD echoed
        assertThat(flags & 0xF).isEqualTo(DnsWireFormat.RCODE_NO_ERROR);
        assertThat(u16(resp, 4)).isEqualTo(1); // QDCOUNT
        assertThat(u16(resp, 6)).isEqualTo(1); // ANCOUNT
        // the RDATA of the (only) answer is the last 4 bytes of the message
        assertThat(Arrays.copyOfRange(resp, resp.length - 4, resp.length)).isEqualTo(ip);
    }

    @Test
    void buildAnswer_supportsAAAA() throws Exception {
        byte[] q = rawQuery(1, true, "fileserver.homeoffice.islandr.internal", DnsWireFormat.TYPE_AAAA);
        DnsWireFormat.Query parsed = DnsWireFormat.parseQuery(q, q.length);
        byte[] ip6 = InetAddress.getByName("fd11::42").getAddress();

        byte[] resp = DnsWireFormat.buildAnswer(q, parsed, ip6, 30);

        assertThat(Arrays.copyOfRange(resp, resp.length - 16, resp.length)).isEqualTo(ip6);
    }

    @Test
    void buildError_hasNoAnswersAndCarriesRcode() throws Exception {
        byte[] q = rawQuery(7, false, "nope.homeoffice.islandr.internal", DnsWireFormat.TYPE_A);
        DnsWireFormat.Query parsed = DnsWireFormat.parseQuery(q, q.length);

        byte[] resp = DnsWireFormat.buildError(q, parsed, DnsWireFormat.RCODE_NXDOMAIN);

        assertThat(u16(resp, 6)).isEqualTo(0); // ANCOUNT
        assertThat(u16(resp, 2) & 0xF).isEqualTo(DnsWireFormat.RCODE_NXDOMAIN);
    }

    @Test
    void buildQuery_roundTripsThroughParseQuery() throws Exception {
        byte[] q = DnsWireFormat.buildQuery(0xABCD, "www.example.com", DnsWireFormat.TYPE_A);
        DnsWireFormat.Query parsed = DnsWireFormat.parseQuery(q, q.length);

        assertThat(parsed.id()).isEqualTo(0xABCD);
        assertThat(parsed.recursionDesired()).isTrue();
        assertThat(parsed.name()).isEqualTo("www.example.com");
        assertThat(parsed.qtype()).isEqualTo(DnsWireFormat.TYPE_A);
    }

    @Test
    void parseFirstAnswerAddress_extractsAnARecord() throws Exception {
        byte[] resp = rawResponseWithAnswer("www.example.com", DnsWireFormat.TYPE_A,
                InetAddress.getByName("93.184.216.34").getAddress());

        assertThat(DnsWireFormat.parseFirstAnswerAddress(resp)).isEqualTo("93.184.216.34");
    }

    @Test
    void parseFirstAnswerAddress_extractsAnAAAARecord() throws Exception {
        byte[] resp = rawResponseWithAnswer("www.example.com", DnsWireFormat.TYPE_AAAA,
                InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946").getAddress());

        assertThat(DnsWireFormat.parseFirstAnswerAddress(resp)).isEqualTo("2606:2800:220:1:248:1893:25c8:1946");
    }

    @Test
    void parseFirstAnswerAddress_returnsNull_whenThereAreNoAnswers() throws Exception {
        byte[] q = rawQuery(1, true, "nope.example.com", DnsWireFormat.TYPE_A);
        // A response with the same header shape but ANCOUNT=0 (NXDOMAIN-style upstream reply).
        assertThat(DnsWireFormat.parseFirstAnswerAddress(q)).isNull();
    }

    @Test
    void parseFirstAnswerAddress_returnsNull_ratherThanThrowing_onGarbageInput() {
        assertThat(DnsWireFormat.parseFirstAnswerAddress(new byte[]{1, 2, 3})).isNull();
        assertThat(DnsWireFormat.parseFirstAnswerAddress(new byte[0])).isNull();
    }

    /** Builds a synthetic upstream-style response: the given query's question
     *  section echoed back, plus one answer RR of the given type/address. */
    private static byte[] rawResponseWithAnswer(String name, int type, byte[] address) throws Exception {
        byte[] q = rawQuery(1, true, name, type);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, 1); // ID
        writeU16(out, 0x8180); // QR=1, RD=1, RA=1, no error
        writeU16(out, 1); // QDCOUNT
        writeU16(out, 1); // ANCOUNT
        writeU16(out, 0);
        writeU16(out, 0);
        out.write(q, 12, q.length - 12); // question section verbatim
        writeU16(out, 0xC00C); // NAME: pointer to the question name
        writeU16(out, type);
        writeU16(out, DnsWireFormat.CLASS_IN);
        out.write(0); out.write(0); out.write(0); out.write(30); // TTL
        writeU16(out, address.length); // RDLENGTH
        out.write(address);
        return out.toByteArray();
    }

    private static byte[] rawQuery(int id, boolean recursionDesired, String name, int qtype) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, id);
        writeU16(out, recursionDesired ? 0x0100 : 0);
        writeU16(out, 1); // QDCOUNT
        writeU16(out, 0); // ANCOUNT
        writeU16(out, 0); // NSCOUNT
        writeU16(out, 0); // ARCOUNT
        for (String label : name.split("\\.")) {
            byte[] l = label.getBytes(StandardCharsets.US_ASCII);
            out.write(l.length);
            out.write(l);
        }
        out.write(0); // root label
        writeU16(out, qtype);
        writeU16(out, DnsWireFormat.CLASS_IN);
        return out.toByteArray();
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static int u16(byte[] d, int off) {
        return ((d[off] & 0xff) << 8) | (d[off + 1] & 0xff);
    }
}
