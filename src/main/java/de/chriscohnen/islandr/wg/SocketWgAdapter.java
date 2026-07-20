package de.chriscohnen.islandr.wg;

import de.chriscohnen.islandr.proxy.ProxyClient;
import de.chriscohnen.islandr.proxy.ProxyResponse;
import de.chriscohnen.islandr.proxy.ProxyUnavailableException;
import org.jboss.logging.Logger;

import javax.crypto.KeyAgreement;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link WgAdapter} for the {@code islandr.wg.mode=socket} runtime: an unprivileged
 * container talking to a host-side {@code islandr-proxy} over a Unix socket
 * (ADR-0012, design §3/§4).
 *
 * <p>Two responsibilities split by whether privilege is needed:
 * <ul>
 *   <li><b>Userspace crypto</b> — {@link #genKeypair()}, {@link #derivePublicKey(String)},
 *       {@link #genPsk()} run entirely in-JVM via the JDK X25519 provider. They need
 *       no kernel access and no {@code wg} binary, so the container image stays minimal.
 *       Key encoding is raw Curve25519 (little-endian, 32 bytes, base64) — the same
 *       WireGuard uses — verified against the RFC 7748 test vector.
 *   <li><b>Privileged ops</b> — {@link #setPeer}, {@link #removePeer}, {@link #showPeers},
 *       {@link #probeServer} map to JSON ops sent through {@link ProxyClient}. The
 *       interface is fixed to {@code wg0} on the host side, so the {@code iface} argument
 *       is not forwarded. A reachable proxy reporting {@code ok:false} becomes a
 *       {@link WgException}; an unreachable proxy raises {@link ProxyUnavailableException}
 *       so the call-site can degrade honestly.
 * </ul>
 *
 * <p>{@link #setIfMtu} is a no-op here: it would need {@code ip link}, which is not in the
 * proxy allowlist (design §4).
 */
public class SocketWgAdapter implements WgAdapter {

    private static final Logger LOG = Logger.getLogger(SocketWgAdapter.class);
    private static final BigInteger CURVE25519_BASEPOINT_U = BigInteger.valueOf(9);

    private final ProxyClient client;
    private final SecureRandom random = new SecureRandom();

    public SocketWgAdapter(ProxyClient client) {
        this.client = client;
    }

    // ── userspace crypto (in-JVM, no proxy) ──────────────────────────────────

    @Override
    public Keypair genKeypair() {
        byte[] scalar = new byte[32];
        random.nextBytes(scalar);
        String privateKey = Base64.getEncoder().encodeToString(scalar);
        return new Keypair(privateKey, derivePublicKey(privateKey));
    }

    @Override
    public String derivePublicKey(String privateKey) {
        if (privateKey == null || privateKey.isBlank()) {
            throw new WgException("derivePublicKey: empty private key");
        }
        byte[] scalar = Base64.getDecoder().decode(privateKey);
        try {
            KeyFactory kf = KeyFactory.getInstance("XDH");
            PrivateKey priv = kf.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, scalar));
            PublicKey basepoint = kf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, CURVE25519_BASEPOINT_U));

            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(priv);
            ka.doPhase(basepoint, true);
            // X25519(scalar, basepoint) is exactly the WireGuard public key, RFC 7748
            // little-endian encoding — the same bytes generateSecret returns.
            return Base64.getEncoder().encodeToString(ka.generateSecret());
        } catch (GeneralSecurityException e) {
            throw new WgException("X25519 public-key derivation failed", e);
        }
    }

    @Override
    public String genPsk() {
        byte[] psk = new byte[32];
        random.nextBytes(psk);
        return Base64.getEncoder().encodeToString(psk);
    }

    // ── privileged ops (through the proxy) ───────────────────────────────────

    @Override
    public void setPeer(String iface, String publicKey, String allowedIps, String presharedKey) {
        Map<String, Object> request = new HashMap<>();
        request.put("op", "wg_set_peer");
        request.put("pubkey", publicKey);
        request.put("allowedIps", allowedIps);
        if (presharedKey != null) {
            request.put("presharedKey", presharedKey);
        }
        expectOk(client.send(request), "wg_set_peer");
    }

    @Override
    public void removePeer(String iface, String publicKey) {
        expectOk(client.send(Map.of("op", "wg_remove_peer", "pubkey", publicKey)), "wg_remove_peer");
    }

    @Override
    public List<PeerStatus> showPeers(String iface) {
        ProxyResponse response = client.send(Map.of("op", "wg_show"));
        expectOk(response, "wg_show");
        String dump = response.body().path("dump").asText("");
        return RealWgAdapter.parseShowDump(dump);
    }

    @Override
    public void setIfMtu(String iface, int mtu) {
        // No `ip link` in the proxy allowlist (design §4). MTU tuning is a no-op in socket mode.
        LOG.debugf("socket mode: setIfMtu(%s, %d) is a no-op (ip link not proxied)", iface, mtu);
    }

    @Override
    public ProbeResult probeServerDetailed(String iface) {
        ProxyResponse response;
        try {
            response = client.send(Map.of("op", "wg_show"));
        } catch (ProxyUnavailableException e) {
            LOG.debugf("socket mode: probeServer unreachable: %s", e.getMessage());
            return ProbeResult.failed(e.getMessage());
        }
        if (!response.ok()) {
            return ProbeResult.failed(response.error());
        }
        String dump = response.body().path("dump").asText("");
        String[] lines = dump.split("\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            return ProbeResult.failed("wg_show: empty dump");
        }
        String[] fields = lines[0].trim().split("\t");
        if (fields.length < 3) {
            return ProbeResult.failed("wg_show: unexpected output format");
        }
        String publicKey = fields[1];
        int listenPort;
        try {
            listenPort = Integer.parseInt(fields[2]);
        } catch (NumberFormatException e) {
            listenPort = 51820;
        }
        int peerCount = (int) java.util.Arrays.stream(lines).skip(1).filter(l -> !l.isBlank()).count();
        // ifStatus/mtu come from `ip link`, which is not proxied — report unknown.
        return ProbeResult.ok(new ServerInfo(publicKey, listenPort, peerCount, "unknown", 0));
    }

    /** A reachable proxy that reports {@code ok:false} is an operational failure → WgException. */
    private static void expectOk(ProxyResponse response, String op) {
        if (!response.ok()) {
            throw new WgException(op + " failed: " + response.error());
        }
    }
}
