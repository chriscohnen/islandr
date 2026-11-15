package de.chriscohnen.islandr.backup;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 for S3 requests, by hand (backup-s3) — the whole
 * point of S3 compatibility is that any bucket speaking the protocol works
 * (AWS, Cloudflare R2, Backblaze B2, MinIO), and an SDK would add a large
 * dependency to a native image for the three calls we make.
 *
 * <p>Only what those calls need: header-signed requests, no presigned URLs, no
 * chunked uploads. Verified against the worked example in the AWS
 * documentation (see {@code S3SignerTest}).
 */
final class S3Signer {

    private S3Signer() {}

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 3986 percent-encoding as S3 wants it: unreserved characters stay, everything else is %XX. */
    static String encode(String s, boolean keepSlash) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved || (keepSlash && c == '/')) out.append(c);
            else out.append('%').append(String.format("%02X", b & 0xff));
        }
        return out.toString();
    }

    /** Query parameters sorted by name and encoded, the form both the URL and the signature use. */
    static String canonicalQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(encode(e.getKey(), false)).append('=').append(encode(e.getValue(), false));
        }
        return sb.toString();
    }

    /**
     * The value of the {@code Authorization} header.
     *
     * @param headers every header to sign, names in lower case — must include
     *                {@code host}, {@code x-amz-content-sha256} and {@code x-amz-date}
     * @param amzDate {@code yyyyMMdd'T'HHmmss'Z'}
     */
    static String authorization(String accessKey, String secretKey, String region,
                                String method, String canonicalUri, String canonicalQuery,
                                Map<String, String> headers, String payloadHash, String amzDate) {
        TreeMap<String, String> sorted = new TreeMap<>(headers);
        StringBuilder canonHeaders = new StringBuilder();
        StringBuilder signed = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            canonHeaders.append(e.getKey()).append(':').append(e.getValue().strip()).append('\n');
            if (signed.length() > 0) signed.append(';');
            signed.append(e.getKey());
        }
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + canonHeaders + "\n" + signed + "\n" + payloadHash;

        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(kSigning, stringToSign));

        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signed + ", Signature=" + signature;
    }
}
