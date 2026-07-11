package de.chriscohnen.islandr.discovery;

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
            List.of(22, 80, 443, 445, 554, 631, 3389, 5900, 8006, 8080, 8123, 8443, 9100);

    /** A fixed, likely-closed high UDP port; a port-unreachable back from it proves liveness. */
    public static final int DEFAULT_UDP_PROBE_PORT = 40125;

    private final List<Integer> tcpPorts;
    private final int udpProbePort;
    private final int timeoutMillis;

    public HostProbe(List<Integer> tcpPorts, int udpProbePort, Duration timeout) {
        this.tcpPorts = List.copyOf(tcpPorts);
        this.udpProbePort = udpProbePort;
        this.timeoutMillis = (int) Math.max(1, timeout.toMillis());
    }

    /** Result for one host: whether it is up, and which probed TCP ports are open. */
    public record ProbeResult(String ip, boolean live, List<Integer> openPorts) {}

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
        return new ProbeResult(ip, live, List.copyOf(open));
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
