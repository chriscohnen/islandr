package de.chriscohnen.islandr.wg;

import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-memory {@link WgAdapter} for dev on hosts without a live wg interface
 * (macOS, CI) and for unit tests.
 *
 * <p>Keypairs are random Curve25519-shaped 32-byte values base64-encoded.
 * They are <b>not</b> cryptographically meaningful — never use this on a real
 * tunnel. Peer state is kept in an in-memory map; a "last handshake" timestamp
 * is filled in for ~70% of peers on each {@link #showPeers} call to simulate
 * a realistic mix of online/offline peers for the UI.
 */
public class MockWgAdapter implements WgAdapter {

    private static final Logger LOG = Logger.getLogger(MockWgAdapter.class);

    // Lazily initialised — GraalVM native-image refuses to seed a SecureRandom
    // during build-time bean instantiation (the seed source is a native handle).
    private volatile SecureRandom rng;

    private final Map<String, MockPeer> peers = new LinkedHashMap<>();

    private record MockPeer(String publicKey, String allowedIps, Instant addedAt) {}

    @Override
    public Keypair genKeypair() {
        byte[] priv = new byte[32];
        rng().nextBytes(priv);
        String privateKey = Base64.getEncoder().encodeToString(priv);
        return new Keypair(privateKey, derivePublicKey(privateKey));
    }

    private SecureRandom rng() {
        SecureRandom r = rng;
        if (r == null) {
            synchronized (this) {
                r = rng;
                if (r == null) {
                    r = new SecureRandom();
                    rng = r;
                }
            }
        }
        return r;
    }

    /**
     * Deterministic pseudo-derivation: SHA-256 over the private-key bytes,
     * base64-encoded. Same input → same output, which is all the unit tests
     * need to assert pairing behaviour. Not cryptographically related to
     * Curve25519 — never use the mock adapter against a real tunnel.
     */
    @Override
    public String derivePublicKey(String privateKey) {
        if (privateKey == null || privateKey.isBlank()) {
            throw new WgException("derivePublicKey: empty private key");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(privateKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new WgException("SHA-256 unavailable in this JVM", e);
        }
    }

    @Override
    public synchronized void setPeer(String iface, String publicKey, String allowedIps) {
        LOG.debugf("mock: setPeer iface=%s pubkey=%s allowed=%s", iface, abbreviate(publicKey), allowedIps);
        peers.put(publicKey, new MockPeer(publicKey, allowedIps, Instant.now()));
    }

    @Override
    public synchronized void removePeer(String iface, String publicKey) {
        LOG.debugf("mock: removePeer iface=%s pubkey=%s", iface, abbreviate(publicKey));
        peers.remove(publicKey);
    }

    @Override
    public synchronized List<PeerStatus> showPeers(String iface) {
        List<PeerStatus> out = new ArrayList<>(peers.size());
        for (MockPeer p : peers.values()) {
            boolean active = ThreadLocalRandom.current().nextDouble() < 0.7;
            Instant lastHandshake = active
                    ? Instant.now().minusSeconds(ThreadLocalRandom.current().nextInt(180))
                    : null;
            long rx = active ? ThreadLocalRandom.current().nextLong(1_000_000, 50_000_000) : 0;
            long tx = active ? ThreadLocalRandom.current().nextLong(1_000_000, 50_000_000) : 0;
            String endpoint = active
                    ? "203.0.113." + ThreadLocalRandom.current().nextInt(2, 254) + ":51820"
                    : null;
            out.add(new PeerStatus(p.publicKey, endpoint, p.allowedIps, lastHandshake, rx, tx));
        }
        return out;
    }

    @Override
    public ServerInfo probeServer(String iface) {
        return new ServerInfo("MOCK+PublicKey+ProbeResult+AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 51820, 0, "unknown", 0);
    }

    /** Test-only hook to reset state between tests. */
    public synchronized void reset() {
        peers.clear();
    }

    private static String abbreviate(String publicKey) {
        return publicKey.length() > 8 ? publicKey.substring(0, 8) + "…" : publicKey;
    }
}
