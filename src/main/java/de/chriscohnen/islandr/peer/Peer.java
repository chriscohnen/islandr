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

    @Column(name = "user_id", nullable = false, length = 36)
    public String userId;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "public_key", nullable = false, length = 44, unique = true)
    public String publicKey;

    @Column(name = "assigned_ip", nullable = false, length = 45, unique = true)
    public String assignedIp;

    @Column(name = "enabled", nullable = false)
    public boolean enabled = true;

    /**
     * Only populated when {@code islandr.peer.privateKey.retention=plaintext}.
     * Always {@code null} in the default {@code never} mode. Never serialised
     * via DTO — re-display goes through PeerService.renderConf().
     * See <a href="../../../../../../../docs/adr/0007-private-key-retention.md">ADR-0007</a>.
     */
    @Column(name = "private_key_pem", length = 44)
    public String privateKeyPem;

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

    public boolean isSite() {
        return "site".equals(type);
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
        p.type = "client";
        return p;
    }
}
