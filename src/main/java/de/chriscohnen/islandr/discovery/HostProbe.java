package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.dns.MdnsLookup;
import de.chriscohnen.islandr.dns.NetBiosLookup;
import de.chriscohnen.islandr.dns.PtrLookup;

import java.io.IOException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Unprivileged host liveness + service probe (ADR-0014, §1).
 *
 * <p>Uses only user-space sockets — a TCP {@code connect()} and a connected UDP
 * {@link DatagramSocket} — so it needs no raw socket, no {@code CAP_NET_RAW}, and
 * no {@code sudoers} entry, keeping the ADR-0011 privilege model intact.
 *
 * <p>A host counts as <em>live</em> if any TCP probe answered — <b>open</b>
 * (connect succeeded) or <b>closed</b> (connection refused) both prove the host
 * is up — or the UDP probe drew an ICMP <i>port unreachable</i>
 * ({@link PortUnreachableException}) even when no TCP port is open. A timeout is
 * ambiguous (down or filtered) and yields nothing. Open TCP ports double as the
 * service fingerprint used later for the resource-type guess.
 *
 * <p>The probe-port set is injected so the engine is unit-testable against a
 * localhost {@code ServerSocket}; production uses {@link #DEFAULT_TCP_PORTS}.
 */
public class HostProbe {

    /** Fixed TCP probe set (ADR-0014): management / console / web / camera ports. */
    public static final List<Integer> DEFAULT_TCP_PORTS =
            List.of(22, 80, 443, 445, 554, 631, 3389, 5900, 7222, 8006, 8080, 8123, 8443, 9100, 9443);

    /** A fixed, likely-closed high UDP port; a port-unreachable back from it proves liveness. */
    public static final int DEFAULT_UDP_PROBE_PORT = 40125;

    /**
     * Reverse-DNS lookups run on a shared daemon pool so a slow/unreachable resolver
     * cannot stall a scan thread — each lookup is bounded by {@link #reverseLookup}.
     * Only performed for hosts already found live, so the pool stays small.
     */
    private static final ExecutorService DNS_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "discovery-ptr");
        t.setDaemon(true);
        return t;
    });

    private final List<Integer> tcpPorts;
    private final int udpProbePort;
    private final int timeoutMillis;
    private final String dnsServerIp;

    public HostProbe(List<Integer> tcpPorts, int udpProbePort, Duration timeout) {
        this(tcpPorts, udpProbePort, timeout, null);
    }

    /** @param dnsServerIp optional site-configured local DNS server (issue
     *         #45) for a targeted PTR lookup, tried before the system
     *         resolver; null = original system-resolver-only behavior. */
    public HostProbe(List<Integer> tcpPorts, int udpProbePort, Duration timeout, String dnsServerIp) {
        this.tcpPorts = List.copyOf(tcpPorts);
        this.udpProbePort = udpProbePort;
        this.timeoutMillis = (int) Math.max(1, timeout.toMillis());
        this.dnsServerIp = dnsServerIp;
    }

    /**
     * Result for one host: whether it is up, which probed TCP ports are open, and its
     * reverse-DNS name if one could be resolved (null otherwise). The hostname is a
     * best-effort convenience for pre-filling the resource name — it depends on the
     * hub being able to reverse-resolve the address (typical on a LAN with a local
     * resolver; often absent for a remote site reached over the tunnel).
     */
    public record ProbeResult(String ip, boolean live, List<Integer> openPorts, String hostname) {}

    public ProbeResult probe(String ip) {
        List<Integer> open = new ArrayList<>();
        boolean live = false;
        for (int port : tcpPorts) {
            switch (probeTcp(ip, port)) {
                case OPEN -> { open.add(port); live = true; }
                case CLOSED -> live = true;      // refused == host is up, port closed
                case NO_ANSWER -> { /* ambiguous: down or filtered */ }
            }
        }
        // Only pay for the UDP probe when TCP found nothing — it just adds hosts
        // that expose none of the probed TCP ports but still emit ICMP.
        if (!live && probeUdpUnreachable(ip)) {
            live = true;
        }
        // Name only live hosts (bounded count), so a slow resolver never taxes a dead sweep.
        String hostname = live ? resolveHostname(ip) : null;
        return new ProbeResult(ip, live, List.copyOf(open), hostname);
    }

    /**
     * Resolution order (issue #48, following on from #45): a router-registered
     * PTR name (targeted against the site's configured DNS server, or the
     * JVM's system resolver) is authoritative and tried first; mDNS and
     * NetBIOS are both device-self-reported fallbacks for hosts no
     * router/local resolver knows a name for. Any step may legitimately come
     * up empty — the admin-typed baseline (handled by the caller, not here)
     * is the final fallback when all three do.
     */
    private String resolveHostname(String ip) {
        if (dnsServerIp != null && !dnsServerIp.isBlank()) {
            Optional<String> targeted = PtrLookup.lookup(ip, dnsServerIp, Duration.ofMillis(Math.min(timeoutMillis, 1500)));
            if (targeted.isPresent()) return targeted.get();
        }
        String system = reverseLookup(ip);
        if (system != null) return system;

        Optional<String> mdns = MdnsLookup.lookup(ip, Duration.ofMillis(Math.min(timeoutMillis, 1500)));
        if (mdns.isPresent()) return mdns.get();

        Optional<String> netbios = NetBiosLookup.lookup(ip, Duration.ofMillis(Math.min(timeoutMillis, 1500)));
        return netbios.orElse(null);
    }

    /** Bounded reverse-DNS (PTR) lookup; returns the name, or null if none / on timeout. */
    private String reverseLookup(String ip) {
        Future<String> f = DNS_POOL.submit(() -> {
            InetAddress a = InetAddress.getByName(ip);
            String h = a.getCanonicalHostName();
            // No PTR record → getCanonicalHostName echoes the literal address back.
            return (h == null || h.isBlank() || h.equals(a.getHostAddress())) ? null : h;
        });
        try {
            return f.get(Math.min(timeoutMillis, 1500), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return null;
        }
    }

    private enum Tcp { OPEN, CLOSED, NO_ANSWER }

    private Tcp probeTcp(String ip, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMillis);
            return Tcp.OPEN;
        } catch (ConnectException e) {
            // "Connection refused" — the host answered, the port is closed.
            return Tcp.CLOSED;
        } catch (IOException e) {
            // Timeout / no route / network unreachable — ambiguous, no signal.
            return Tcp.NO_ANSWER;
        }
    }

    private boolean probeUdpUnreachable(String ip) {
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(timeoutMillis);
            sock.connect(InetAddress.getByName(ip), udpProbePort);
            sock.send(new DatagramPacket(new byte[]{0}, 1));
            sock.receive(new DatagramPacket(new byte[16], 16));
            return false; // an actual reply — unexpected; not our liveness signal
        } catch (PortUnreachableException e) {
            return true;  // ICMP port unreachable delivered on the connected socket → up
        } catch (IOException e) {
            return false; // timeout or other — no liveness signal
        }
    }
}
