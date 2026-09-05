package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.validation.ValidIpAddress;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
            String assignedIpv6,
            boolean enabled,
            Instant lastSeenAt,
            String lastSeenEndpoint,
            String connectionStatus,    // "CONNECTED" | "STALE" | "DISCONNECTED" — see PeerConnectionStatus
            long totalRxBytes,
            long totalTxBytes,
            Instant createdAt,
            Instant updatedAt,
            String type,                // "client" | "site"
            String siteAllowedCidrs,    // null for client peers
            String deviceType,          // laptop | desktop | mobile | tablet | server | other | null
            boolean hasPresharedKey,    // true when a PSK is stored for this peer
            // Null = never rotated since creation (issue #46).
            Instant keyRotatedAt,
            Instant pskRotatedAt,
            Integer mtu,                // null = no per-peer override; use global setting
            Integer persistentKeepalive,// null = no per-peer override; 0 = off; else interval (s)
            boolean includeDns,         // false = never write the DNS line for this peer
            Double lat,                 // site peers only — physical location of the gateway device
            Double lng,
            String locationLabel,
            Instant validUntil,         // null = never expires (issue #10/#47)
            String enabledSource        // "manual" | "schedule" | null (never toggled by either)
    ) {
        public static Response from(Peer p) {
            return new Response(
                    p.id, p.userId, p.name, p.publicKey, p.assignedIp, p.assignedIpv6,
                    p.enabled, p.lastSeenAt, p.lastSeenEndpoint,
                    p.connectionStatus(Instant.now()).name(),
                    p.totalRxBytes, p.totalTxBytes, p.createdAt, p.updatedAt,
                    p.type, p.siteAllowedCidrs, p.deviceType,
                    p.presharedKey != null && !p.presharedKey.isBlank(),
                    p.keyRotatedAt, p.pskRotatedAt,
                    p.mtu, p.persistentKeepalive, p.includeDns,
                    p.lat, p.lng, p.locationLabel,
                    p.validUntil, p.enabledSource);
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
            @NotBlank @ValidIpAddress
            String assignedIp,

            // Optional IPv6 address for dual-stack peers. Validated against wgSubnet6 in service.
            @ValidIpAddress
            String assignedIpv6,

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

            // Required when type='site'. Comma-separated CIDR list.
            String siteAllowedCidrs,

            // Optional cosmetic device category for client peers.
            @Pattern(regexp = "^$|^(laptop|desktop|mobile|tablet|server|other)$",
                    message = "deviceType must be one of: laptop, desktop, mobile, tablet, server, other")
            String deviceType,

            // Optional per-peer MTU override (576–65535). null = use global setting.
            @Min(576) @Max(65535) Integer mtu,

            // When true, the server generates and stores a preshared key for this peer.
            boolean generatePresharedKey,

            // Optional geocoding — meaningful for type='site' only (physical gateway
            // device location); ignored/cleared for client peers.
            Double lat,
            Double lng,
            String locationLabel
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
            String qrPngBase64,
            String presharedKey    // null when no PSK was generated for this peer
    ) {}

    // reason: optional, only meaningful when disabling — stored in the audit
    // log detail only (no persisted column, see #47 design notes).
    public record EnabledRequest(boolean enabled, String reason) {}

    /**
     * Mutable subset of a peer's state. Type and public key are not editable —
     * use delete + create for a key rotation. The type IS switchable — an
     * imported peer that should have been a site gateway would otherwise have to
     * be deleted and recreated, losing its activity history.
     */
    public record UpdateRequest(
            @NotBlank String name,
            @NotBlank @ValidIpAddress
            String assignedIp,

            // Optional IPv6 address for dual-stack peers.
            @ValidIpAddress
            String assignedIpv6,

            // Required for site peers; rejected for client peers.
            String siteAllowedCidrs,

            @Pattern(regexp = "^$|^(laptop|desktop|mobile|tablet|server|other)$",
                    message = "deviceType must be one of: laptop, desktop, mobile, tablet, server, other")
            String deviceType,

            // PSK rotation action. null = leave unchanged; "rotate" = generate new PSK;
            // "remove" = clear the PSK (both sides must update their configs).
            @Pattern(regexp = "^$|^(rotate|remove)$",
                    message = "presharedKeyAction must be 'rotate', 'remove', or omitted")
            String presharedKeyAction,

            // Optional per-peer MTU override (576–65535). null = use global setting.
            @Min(576) @Max(65535) Integer mtu,

            // Optional per-peer PersistentKeepalive override (0–65535 seconds).
            // null = defer to global setting; 0 = keepalive off for this peer.
            @Min(0) @Max(65535) Integer persistentKeepalive,

            // Whether to write the global DNS line into this peer's .conf/QR, when
            // one is configured. Boolean (not boolean): a request that omits this
            // field must keep the current behaviour (true), not silently flip to
            // false via Jackson's missing-primitive default.
            Boolean includeDns,

            // Optional geocoding — meaningful for type='site' only.
            Double lat,
            Double lng,
            String locationLabel,

            // One-time terminal expiry (issue #10/#47). null = leave unchanged
            // is NOT the semantics here — omitting the field clears validUntil,
            // same as every other nullable field in this record; the frontend
            // always sends the peer's current value back unless the admin
            // explicitly changed it.
            Instant validUntil,

            // "client" | "site". null (field omitted) leaves the current type
            // alone — unlike the other nullable fields here, because a client
            // that silently became a site would drop its owning user.
            @Pattern(regexp = "^$|^(client|site)$",
                    message = "type must be 'client' or 'site'")
            String type,

            // Owning user. Omitted (null) leaves the current owner alone; ""
            // unassigns the peer; an id assigns it. Deliberately NOT the
            // omitted-means-cleared rule the other nullable fields here use:
            // RuleBuilder derives every ACL grant from peer.userId, so a
            // request that merely forgot the field must not strip a client of
            // all its access. Rejected for type='site' — a site gateway is
            // owned by the network, not by a person.
            String userId
    ) {}

    /** Response shape for {@code GET /api/v1/peers/next-ip}. */
    public record NextIpResponse(String assignedIp) {}

    /** Response shape for {@code GET /api/v1/peers/next-ip6}. */
    public record NextIpv6Response(String assignedIpv6) {}

    /**
     * One peer from {@code wg show <iface> dump} that is not yet in the Islandr DB.
     * Returned by {@code GET /api/v1/peers/wg-import-preview}.
     */
    public record WgImportCandidate(
            String publicKey,
            String allowedIps,   // as reported by wg, e.g. "10.8.0.5/32"
            String assignedIp,   // first IPv4 address stripped from allowedIps; null if none
            String assignedIpv6, // first IPv6 address stripped from allowedIps; null if none
            String endpoint,     // last known endpoint IP:port, null if never connected
            // Networks routed behind this peer: every AllowedIP that is not the
            // peer's own host address. Non-null means wg is already treating this
            // as a gateway, which is what makes it a site peer rather than a
            // client — the import offers it pre-selected as such.
            String siteAllowedCidrs,
            boolean alreadyExists
    ) {}

    /** One entry in a {@code POST /api/v1/peers/wg-import} request. */
    public record WgImportEntry(
            @NotBlank String publicKey,
            @NotBlank String name,
            @NotBlank @ValidIpAddress
            String assignedIp,
            String userId,       // optional — peer may be unassigned; ignored for type='site'
            @Pattern(regexp = "^$|^(client|site)$",
                    message = "type must be 'client' or 'site'")
            String type,         // "client" | "site", defaults to "client"
            // Required for type='site', rejected otherwise. Normally carried over
            // from what wg already reports as AllowedIPs for this peer.
            String siteAllowedCidrs
    ) {}

    /** Request body for {@code POST /api/v1/peers/wg-import}. */
    public record WgImportRequest(
            @jakarta.validation.Valid
            @jakarta.validation.constraints.NotNull
            java.util.List<WgImportEntry> peers
    ) {}

    /** Result of one imported peer. */
    public record WgImportResult(
            String publicKey,
            String status,   // "imported" | "skipped" (already exists)
            String peerId    // null when skipped
    ) {}

    /** One row of the dashboard's connection activity heatmap (#32, #77).
     *  {@code deviceType} lets the row show the same laptop/desktop/mobile/
     *  tablet/server icon PeersView.js already uses — null for a site peer
     *  (rendered with a distinct gateway icon instead) or an unset client
     *  peer. */
    public record ActivityHeatmapRow(
            String peerId,
            String name,
            String type,   // "client" | "site"
            String deviceType,  // laptop | desktop | mobile | tablet | server | other | null
            java.util.List<Integer> sampleHits,  // one entry per day in ActivityHeatmapResponse.days, same order
            java.util.List<Long> rxBytes,        // ditto — rx bytes for that day
            java.util.List<Long> txBytes         // ditto — tx bytes for that day
    ) {}

    /** Peers x days activity matrix. {@code days} is ascending ISO-8601 date
     *  strings (YYYY-MM-DD); each row's {@code sampleHits} aligns positionally
     *  with {@code days}. */
    public record ActivityHeatmapResponse(
            java.util.List<String> days,
            java.util.List<ActivityHeatmapRow> peers
    ) {}

    private PeerDto() {}
}
