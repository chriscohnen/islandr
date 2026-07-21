package de.chriscohnen.islandr.acme;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DerTest {

    @Test
    void length_shortForm_under128() {
        assertThat(Der.length(0)).containsExactly(0x00);
        assertThat(Der.length(1)).containsExactly(0x01);
        assertThat(Der.length(127)).containsExactly(0x7F);
    }

    @Test
    void length_longForm_128AndAbove() {
        // 128 = 0x80 needs one length-of-length byte (0x81) then the value byte.
        assertThat(Der.length(128)).containsExactly(0x81, 0x80);
        assertThat(Der.length(255)).containsExactly(0x81, 0xFF);
        // 256 needs two content bytes.
        assertThat(Der.length(256)).containsExactly(0x82, 0x01, 0x00);
        assertThat(Der.length(65535)).containsExactly(0x82, 0xFF, 0xFF);
    }

    @Test
    void tagged_prependsTagAndLength() {
        byte[] out = Der.tagged(0x04, new byte[]{1, 2, 3});
        assertThat(out).containsExactly(0x04, 0x03, 1, 2, 3);
    }

    @Test
    void integer_positiveNoLeadingZeroNeeded() {
        // 0x7F (127) — high bit clear, no padding needed.
        byte[] out = Der.integer(new byte[]{0x7F});
        assertThat(out).containsExactly(0x02, 0x01, 0x7F);
    }

    @Test
    void integer_highBitSetGetsZeroPadByte() {
        // 0x80 alone would read as negative in two's-complement DER — must be
        // padded with a leading 0x00 to stay a positive INTEGER.
        byte[] out = Der.integer(new byte[]{(byte) 0x80});
        assertThat(out).containsExactly(0x02, 0x02, 0x00, (byte) 0x80);
    }

    @Test
    void integer_stripsRedundantLeadingZeros() {
        byte[] out = Der.integer(new byte[]{0x00, 0x00, 0x01});
        assertThat(out).containsExactly(0x02, 0x01, 0x01);
    }

    @Test
    void oid_ecdsaWithSha256_matchesKnownEncoding() {
        // 1.2.840.10045.4.3.2 (ecdsa-with-SHA256) — a well-known, independently
        // verifiable DER encoding (e.g. from any X.509 cert using this signature
        // algorithm): 06 08 2A 86 48 CE 3D 04 03 02
        byte[] out = Der.oid("1.2.840.10045.4.3.2");
        assertThat(out).containsExactly(
                0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02);
    }

    @Test
    void oid_ecPublicKey_matchesKnownEncoding() {
        // 1.2.840.10045.2.1 (id-ecPublicKey): 06 07 2A 86 48 CE 3D 02 01
        byte[] out = Der.oid("1.2.840.10045.2.1");
        assertThat(out).containsExactly(0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01);
    }

    @Test
    void oid_commonName_matchesKnownEncoding() {
        // 2.5.4.3 (commonName): 06 03 55 04 03
        byte[] out = Der.oid("2.5.4.3");
        assertThat(out).containsExactly(0x06, 0x03, 0x55, 0x04, 0x03);
    }

    @Test
    void bitString_prependsZeroUnusedBitsByte() {
        byte[] out = Der.bitString(new byte[]{1, 2, 3});
        assertThat(out).containsExactly(0x03, 0x04, 0x00, 1, 2, 3);
    }

    @Test
    void utf8String_encodesTagAndUtf8Bytes() {
        byte[] out = Der.utf8String("ab");
        assertThat(out).containsExactly(0x0c, 0x02, 'a', 'b');
    }

    @Test
    void contextConstructed_usesTag0xA0ForTagNumberZero() {
        byte[] out = Der.contextConstructed(0, new byte[]{9});
        assertThat(out[0]).isEqualTo((byte) 0xA0);
        assertThat(out).containsExactly(0xA0, 0x01, 0x09);
    }
}
