package de.chriscohnen.islandr.peer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public final class PeerDto {

    /** Public peer state. Never includes the private key — see [docs/prd.md] F-03. */
    public record Response(
            String id,
            String userId,
            String name,
            String publicKey,
            String assignedIp,
            boolean enabled,
            Instant lastSeenAt,
            String lastSeenEndpoint,
            long totalRxBytes,
            long totalTxBytes,
            Instant createdAt,
            String type,                // "client" | "site"
            String siteAllowedCidrs,    // null for client peers
            String deviceType           // laptop | desktop | mobile | tablet | server | other | null
    ) {
        public static Response from(Peer p) {
            return new Response(
                    p.id, p.userId, p.name, p.publicKey, p.assignedIp,
                    p.enabled, p.lastSeenAt, p.lastSeenEndpoint,
                    p.totalRxBytes, p.totalTxBytes, p.createdAt,
                    p.type, p.siteAllowedCidrs, p.deviceType);
        }
    }

    /**
     * Peer creation request. Three modes are derived from {@code publicKey} and
     * {@code privateKey}:
     * <ul>
     *   <li>both blank → server generates a fresh keypair (default, safest for new users)</li>
     *   <li>{@code publicKey} only → admin imports a client-generated public key; the
     *       private key never reaches the server. Reshow/.conf will be served without
     *       a PrivateKey line.</li>
     *   <li>{@code publicKey} + {@code privateKey} → admin imports both, typically when
     *       migrating from PiVPN. The two are pairing-validated server-side and the
     *       private key is stored only when retention=plaintext.</li>
     * </ul>
     * Sending {@code privateKey} without {@code publicKey} is rejected with 400.
     */
    public record CreateRequest(
            @NotBlank String name,
            @NotBlank
            @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$",
                    message = "must be an IPv4 address (e.g. 10.8.0.5)")
            String assignedIp,

            // Optional. Base64-encoded 32-byte WireGuard public key (44 chars incl. '=' padding).
            @Pattern(regexp = "^$|^[A-Za-z0-9+/]{43}=$",
                    message = "must be a 44-char Base64 WireGuard key")
            String publicKey,

            // Optional. Same format as publicKey but a private key.
            @Pattern(regexp = "^$|^[A-Za-z0-9+/]{43}=$",
                    message = "must be a 44-char Base64 WireGuard key")
            String privateKey,

            // Peer kind. Defaults to "client" on the wire when omitted.
            @Pattern(regexp = "^$|^(client|site)$",
                    message = "type must be 'client' or 'site'")
            String type,

            // Required when type='site'. Comma-separated IPv4 CIDR list, e.g.
            // "192.168.50.0/24, 10.20.0.0/16".
            String siteAllowedCidrs,

            // Optional cosmetic device category for client peers.
            @Pattern(regexp = "^$|^(laptop|desktop|mobile|tablet|server|other)$",
                    message = "deviceType must be one of: laptop, desktop, mobile, tablet, server, other")
            String deviceType
    ) {
        public boolean hasPublicKey()  { return publicKey  != null && !publicKey.isBlank(); }
        public boolean hasPrivateKey() { return privateKey != null && !privateKey.isBlank(); }

        /** Resolved type with the "client" default applied. */
        public String resolvedType() {
            return (type == null || type.isBlank()) ? "client" : type;
        }

        public boolean isSite() {
            return "site".equals(resolvedType());
        }
    }

    /**
     * The one-shot creation response. The {@code privateKey} field is the only
     * place this value ever appears server-side; it is not persisted and there is
     * no GET endpoint that returns it again.
     */
    public record CreateResponse(
            Response peer,
            String privateKey,
            String conf,
            String qrPngBase64
    ) {}

    public record EnabledRequest(boolean enabled) {}

    /**
     * Mutable subset of a peer's state. Type and public key are not editable —
     * use delete + create for a key rotation or a type switch.
     *
     * <p>{@code siteAllowedCidrs} is required when the peer is type='site',
     * forbidden when type='client'.
     */
    public record UpdateRequest(
            @NotBlank String name,
            @NotBlank
            @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$",
                    message = "must be an IPv4 address (e.g. 10.8.0.5)")
            String assignedIp,

            // Required for site peers; rejected for client peers.
            String siteAllowedCidrs,

            @Pattern(regexp = "^$|^(laptop|desktop|mobile|tablet|server|other)$",
                    message = "deviceType must be one of: laptop, desktop, mobile, tablet, server, other")
            String deviceType
    ) {}

    /** Response shape for {@code GET /api/v1/peers/next-ip}. */
    public record NextIpResponse(String assignedIp) {}

    private PeerDto() {}
}
