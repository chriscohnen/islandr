package de.chriscohnen.islandr.wg;

import java.time.Instant;
import java.util.List;

/**
 * Wraps the WireGuard control surface ({@code wg}, {@code wg-quick}).
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@link RealWgAdapter} — shells out to the {@code wg} CLI via ProcessBuilder.
 *       Production path; also works on macOS for the userspace-only methods
 *       ({@code genKeypair}, parsing) since wireguard-tools is installed via brew.
 *   <li>{@link MockWgAdapter} — deterministic in-memory implementation. Used in
 *       tests and as the {@code islandr.wg.mode=mock} runtime mode for dev on
 *       hosts that can't run a real wg interface.
 * </ul>
 *
 * <p>Selection happens via the {@code islandr.wg.mode} config property; see
 * {@link WgAdapterProducer}.
 */
public interface WgAdapter {

    /** A freshly generated WireGuard keypair. Private key is base64, 44 chars. */
    record Keypair(String privateKey, String publicKey) {}

    /** State of one peer as reported by {@code wg show <iface> dump}. */
    record PeerStatus(
            String publicKey,
            String presharedKey,       // base64 PSK, or null if "(none)"
            String endpoint,           // IP:port; null if peer has never connected
            String allowedIps,         // CIDR list, e.g. "10.8.0.5/32"
            Instant lastHandshake,     // null if never
            long rxBytes,
            long txBytes
    ) {}

    /** Generate a new WireGuard keypair (private + derived public). */
    Keypair genKeypair();

    /** Generate a new WireGuard preshared key (32-byte random, base64-encoded). */
    String genPsk();

    /**
     * Derive the public key for a given private key. Wraps {@code wg pubkey} in
     * the real adapter; in mock the derivation is deterministic but not
     * cryptographically meaningful, which is fine for unit tests.
     *
     * @throws WgException if the derivation fails or the input is malformed.
     */
    String derivePublicKey(String privateKey);

    /**
     * Add or update a peer on the live interface.
     *
     * @param presharedKey optional preshared key (base64, 44 chars); {@code null} to leave
     *        any existing PSK unchanged. Pass {@code ""} (empty string) to explicitly
     *        clear the PSK on an existing peer.
     */
    void setPeer(String iface, String publicKey, String allowedIps, String presharedKey);

    /** Remove a peer from the live interface. */
    void removePeer(String iface, String publicKey);

    /** Snapshot every peer on the interface. Used by the activity poller. */
    List<PeerStatus> showPeers(String iface);

    /** Set the MTU on a WireGuard interface via {@code ip link set <iface> mtu <mtu>}. */
    void setIfMtu(String iface, int mtu);

    /**
     * Read the server's own public key and listen port from the live interface.
     * Used by the setup wizard to pre-fill settings from an existing WireGuard
     * installation. Returns {@code null} if the interface is not accessible
     * (e.g. Docker without a socket proxy, or interface not yet up).
     *
     * <p>Discards the real failure reason — existing callers (the setup wizard,
     * {@code GET /wg-probe}) only ever needed a yes/no. Implemented in terms of
     * {@link #probeServerDetailed}; override that, not this, to add error detail.
     */
    default ServerInfo probeServer(String iface) {
        return probeServerDetailed(iface).info();
    }

    /**
     * Same probe as {@link #probeServer}, but on failure surfaces the real reason
     * instead of collapsing it to a bare {@code null}. {@code ProxyReconciler} uses
     * this so the enforcement banner can show something more useful than a
     * generic "proxy probe failed" (#37).
     */
    ProbeResult probeServerDetailed(String iface);

    /**
     * State of the WireGuard server interface as read by the probe.
     * Fields sourced from {@code wg show <iface> dump} and {@code ip link show <iface>}.
     * {@code ifStatus} and {@code mtu} are "unknown" when {@code ip link} is not accessible.
     */
    record ServerInfo(
            String publicKey,
            int listenPort,
            int peerCount,
            String ifStatus,  // "up" | "down" | "unknown"
            int mtu           // 0 when unknown
    ) {}

    /** Outcome of {@link #probeServerDetailed}: success carries {@code info}, failure carries {@code error}. */
    record ProbeResult(ServerInfo info, String error) {
        public static ProbeResult ok(ServerInfo info) { return new ProbeResult(info, null); }
        public static ProbeResult failed(String error) { return new ProbeResult(null, error); }
        public boolean reachable() { return info != null; }
    }
}
