package de.chriscohnen.islandr.discovery;

import de.chriscohnen.islandr.dns.LlmnrLookup;
import de.chriscohnen.islandr.dns.MdnsLookup;
import de.chriscohnen.islandr.dns.NetBiosLookup;
import de.chriscohnen.islandr.dns.PtrLookup;
import de.chriscohnen.islandr.dns.SsdpLookup;

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
    private final LinkScope linkScope = new LinkScope();

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
     * Resolution order, most authoritative first: a registered PTR name
     * (targeted at the site's own DNS server, else the JVM's system resolver)
     * is what an authority says the host is called. Everything after it is
     * device-self-reported — what the host claims about itself over mDNS,
     * LLMNR, NetBIOS or SSDP. Any step may legitimately come up empty; the
     * admin-typed baseline (handled by the caller) is the final fallback.
     *
     * <p>The self-report protocols all target the host directly rather than a
     * multicast group. For a hub scanning a network behind a site gateway —
     * the case Islandr exists for — a multicast query never gets there, and
     * the source would silently contribute nothing. NetBIOS was unicast from
     * the start, which is why on such a hub it used to be the only source that
     * ever produced a name.
     *
     * <p>Targeting the host directly is necessary but not sufficient: mDNS and
     * LLMNR responders reject an off-link querier by specification (see
     * {@link LinkScope}), so those two are skipped unless the target sits in
     * one of the hub's own subnets. Off-link they cannot answer, and asking
     * anyway would spend the budget the sources that <em>can</em> answer need.
     *
     * <p>The order among the self-reports is by how much of a hostname each
     * actually is: mDNS and LLMNR answer the machine's own name, NetBIOS the
     * same name truncated to 15 characters, and SSDP a human-facing product
     * label ("Brother HL-L2350DW") that names the device rather than the host.
     * Last is still far better than "computer-42" for the printers, NAS boxes
     * and cameras that answer nothing else.
     */
    private String resolveHostname(String ip) {
        Duration budget = Duration.ofMillis(Math.min(timeoutMillis, 1500));

        if (dnsServerIp != null && !dnsServerIp.isBlank()) {
            Optional<String> targeted = PtrLookup.lookup(ip, dnsServerIp, budget);
            if (targeted.isPresent()) return targeted.get();
        }
        String system = reverseLookup(ip);
        if (system != null) return system;

        if (linkScope.isOnLink(ip)) {
            Optional<String> mdns = MdnsLookup.lookup(ip, budget);
            if (mdns.isPresent()) return mdns.get();

            Optional<String> llmnr = LlmnrLookup.lookup(ip, budget);
            if (llmnr.isPresent()) return llmnr.get();
        }

        Optional<String> netbios = NetBiosLookup.lookup(ip, budget);
        if (netbios.isPresent()) return netbios.get();

        return SsdpLookup.lookup(ip, budget).orElse(null);
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
