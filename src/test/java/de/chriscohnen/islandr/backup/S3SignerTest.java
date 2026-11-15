package de.chriscohnen.islandr.backup;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** backup-s3: the signature is the one part a wrong byte silently breaks, so it is pinned to AWS's own worked example. */
class S3SignerTest {

    private static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** "Example: GET Object" from "Signature Calculations for the Authorization Header" in the S3 API reference. */
    @Test
    void matchesTheAwsGetObjectExample() {
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", EMPTY_SHA);
        headers.put("x-amz-date", "20130524T000000Z");

        String auth = S3Signer.authorization("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "us-east-1", "GET", "/test.txt", "", headers, EMPTY_SHA, "20130524T000000Z");

        assertThat(auth).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + " SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + " Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    /** "Example: GET Bucket Lifecycle" — same key, a query string with an empty value. */
    @Test
    void matchesTheAwsQueryStringExample() {
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("x-amz-content-sha256", EMPTY_SHA);
        headers.put("x-amz-date", "20130524T000000Z");

        String auth = S3Signer.authorization("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "us-east-1", "GET", "/", S3Signer.canonicalQuery(Map.of("lifecycle", "")), headers, EMPTY_SHA,
                "20130524T000000Z");

        assertThat(auth).endsWith("Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543");
    }

    @Test
    void encodesLikeS3Expects() {
        assertThat(S3Signer.encode("a b/c+d~e", true)).isEqualTo("a%20b/c%2Bd~e");
        assertThat(S3Signer.encode("a/b", false)).isEqualTo("a%2Fb");
        assertThat(S3Signer.encode("ä", false)).isEqualTo("%C3%A4");
    }

    @Test
    void sortsQueryParameters() {
        assertThat(S3Signer.canonicalQuery(Map.of("prefix", "x/", "list-type", "2")))
                .isEqualTo("list-type=2&prefix=x%2F");
    }
}
