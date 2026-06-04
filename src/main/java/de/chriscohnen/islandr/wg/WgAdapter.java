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
            String endpoint,           // IP:port; null if peer has never connected
            String allowedIps,         // CIDR list, e.g. "10.8.0.5/32"
            Instant lastHandshake,     // null if never
            long rxBytes,
            long txBytes
    ) {}

    /** Generate a new WireGuard keypair (private + derived public). */
    Keypair genKeypair();

    /**
     * Derive the public key for a given private key. Wraps {@code wg pubkey} in
     * the real adapter; in mock the derivation is deterministic but not
     * cryptographically meaningful, which is fine for unit tests.
     *
     * @throws WgException if the derivation fails or the input is malformed.
     */
    String derivePublicKey(String privateKey);

    /** Add or update a peer on the live interface. */
    void setPeer(String iface, String publicKey, String allowedIps);

    /** Remove a peer from the live interface. */
    void removePeer(String iface, String publicKey);

    /** Snapshot every peer on the interface. Used by the activity poller. */
    List<PeerStatus> showPeers(String iface);
}
