package de.chriscohnen.islandr.peer;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "peers")
public class Peer extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    public String id;

    // Nullable: site peers have no owning user (see migration V37 + commit 43faed0).
    // Client peers always carry a userId, enforced at the service layer.
    @Column(name = "user_id", length = 36)
    public String userId;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "public_key", nullable = false, length = 44, unique = true)
    public String publicKey;

    @Column(name = "assigned_ip", nullable = false, length = 45, unique = true)
    public String assignedIp;

    /** Optional IPv6 address for dual-stack peers. null = IPv4-only peer. */
    @Column(name = "assigned_ip6", length = 45, unique = true)
    public String assignedIpv6;

    @Column(name = "enabled", nullable = false)
    public boolean enabled = true;

    /**
     * Populated in {@code plaintext} mode (raw key) or {@code encrypted} mode (enc$... format).
     * Always {@code null} in the default {@code never} mode. Never serialised via DTO —
     * re-display goes through PeerService which decrypts if needed.
     * See <a href="../../../../../../../docs/adr/0007-private-key-retention.md">ADR-0007</a>.
     */
    @Column(name = "private_key_pem", length = 128)
    public String privateKeyPem;

    /** Set the first time this peer's WireGuard keypair is rotated post-creation
     *  (issue #46's admin-triggered rotation, or the pre-existing self-service
     *  rotation) — null means never rotated since creation. */
    @Column(name = "key_rotated_at")
    public Instant keyRotatedAt;

    @Column(name = "last_seen_at")
    public Instant lastSeenAt;

    @Column(name = "last_seen_endpoint")
    public String lastSeenEndpoint;

    @Column(name = "total_rx_bytes", nullable = false)
    public long totalRxBytes;

    @Column(name = "total_tx_bytes", nullable = false)
    public long totalTxBytes;

    /** Last raw value read from {@code wg show dump}. Used to compute deltas
     *  across counter resets (resets to 0 on interface restart). */
    @Column(name = "last_sampled_rx_bytes", nullable = false)
    public long lastSampledRxBytes;

    @Column(name = "last_sampled_tx_bytes", nullable = false)
    public long lastSampledTxBytes;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** Last time any mutable field on this peer changed (config edit, key rotation,
     *  PSK change, enable/disable). Not touched by read-only activity polling. */
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /**
     * "client" = a single user device (laptop, phone). "site" = a WireGuard
     * gateway that routes traffic for an entire downstream network (branch
     * office, home lab). Determines whether {@link #siteAllowedCidrs} is used.
     */
    @Column(name = "type", nullable = false)
    public String type = "client";

    /**
     * Comma-separated list of IPv4 CIDRs reachable behind this site peer, e.g.
     * "192.168.50.0/24,10.20.0.0/16". {@code null} for client peers. These end
     * up as additional AllowedIPs both in the client .conf and in the hub-side
     * {@code wg set peer ... allowed-ips} call.
     */
    @Column(name = "site_allowed_cidrs")
    public String siteAllowedCidrs;

    /** Cosmetic device category for client peers: laptop, desktop, mobile, tablet, server, other.
     *  NULL for site peers and legacy rows. */
    @Column(name = "device_type", length = 16)
    public String deviceType;

    /**
     * Optional WireGuard preshared key (PSK) for this peer — 32-byte random value, base64-encoded.
     * Provides an additional layer of post-quantum symmetric security on top of the Curve25519
     * key exchange. Always stored in plaintext (unlike the private key) because both sides
     * of the tunnel need to know it; the client receives it in the creation response conf.
     * NULL when no PSK was requested at creation.
     */
    @Column(name = "preshared_key", length = 44)
    public String presharedKey;

    /** Set the first time this peer's PSK is rotated post-creation — null means
     *  never rotated (including a peer that has never had a PSK at all, or one
     *  whose PSK was only ever set once at creation, never rotated since). */
    @Column(name = "psk_rotated_at")
    public Instant pskRotatedAt;

    /** Per-peer MTU written into the client .conf [Interface] section.
     *  null = defer to global setting (Settings.wgMtu / wgIncludeMtuInConf). */
    @Column(name = "mtu")
    public Integer mtu;

    /** Per-peer PersistentKeepalive (seconds) for the client .conf [Peer] section.
     *  null = defer to global setting (Settings.wgPersistentKeepalive);
     *  0 = keepalive explicitly off for this peer; N = explicit interval. */
    @Column(name = "persistent_keepalive")
    public Integer persistentKeepalive;

    /** Whether the client .conf/QR for this peer includes the global DNS line
     *  (Settings.wgClientDns), when one is configured. true (default) = include
     *  it, matching every peer's behaviour before this flag existed. false =
     *  never write it for this peer — e.g. a phone scanning the QR directly
     *  without wanting tunneled DNS. Has no effect when no global DNS is set. */
    @Column(name = "include_dns", nullable = false)
    public boolean includeDns = true;

    /**
     * Optional geocoding for "site"-type peers only — this is the physical gateway
     * device's location (rack, home office), not a property of the logical
     * network(s) it routes (moved off {@code Site} in V47: a site is just a CIDR
     * grouping with no location of its own, and one gateway peer can serve more
     * than one site). Always {@code null} for client peers.
     */
    @Column(name = "lat")
    public Double lat;

    @Column(name = "lng")
    public Double lng;

    @Column(name = "location_label", length = 255)
    public String locationLabel;

    /** One-time, terminal expiry (issue #10/#47) — null = never expires. Once
     *  passed, PeerScheduleJob disables this peer and it stays disabled
     *  regardless of any recurring {@link PeerSchedule}; it does not reactivate
     *  by editing the schedule. Clear it (set null) to lift the expiry. */
    @Column(name = "valid_until")
    public Instant validUntil;

    /** Who last changed {@link #enabled}: "manual" (admin via the API) or
     *  "schedule" (PeerScheduleJob). Null = never toggled by either path since
     *  creation. Lets the scheduler tell its own past flips apart from an
     *  admin override, so a manual disable holds until the schedule's next
     *  open&lt;-&gt;close transition instead of being undone by the next tick. */
    @Column(name = "enabled_source", length = 16)
    public String enabledSource;

    public boolean isSite() {
        return "site".equals(type);
    }

    /** Derived connection state — see {@link PeerConnectionStatus}. */
    public PeerConnectionStatus connectionStatus(Instant now) {
        return PeerConnectionStatus.of(lastSeenAt, now);
    }

    public static Peer createNew(String userId, String name, String publicKey, String assignedIp) {
        Peer p = new Peer();
        p.id = UUID.randomUUID().toString();
        p.userId = userId;
        p.name = name;
        p.publicKey = publicKey;
        p.assignedIp = assignedIp;
        p.enabled = true;
        p.totalRxBytes = 0;
        p.totalTxBytes = 0;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        p.type = "client";
        return p;
    }
}
