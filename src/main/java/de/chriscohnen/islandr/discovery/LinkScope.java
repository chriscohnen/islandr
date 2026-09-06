package de.chriscohnen.islandr.discovery;

import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Answers one question for the name-lookup chain: would the host we are about
 * to ask consider us on its own link?
 *
 * <p>mDNS and LLMNR are link-scope protocols, and their specifications say so
 * in a way that makes an off-link query pointless rather than merely
 * unreliable. RFC 6762 §11 requires an mDNS responder to check that the query's
 * source address "matches the local subnet for that link" and to silently
 * ignore the packet otherwise — which Avahi and Bonjour both implement. RFC
 * 4795 §2.4 defines the unicast form of LLMNR as TCP, and §2.5 has the
 * responder set TTL 1 on that listen socket "so that SYN-ACK packets will have
 * TTL set to one. This prevents an incoming connection from off-link."
 *
 * <p>So for a host behind a site gateway — the case Islandr exists for — those
 * two sources can never produce a name, and trying them only spends the
 * probe's time budget. Measured against a real remote host: a query to UDP
 * 5353/5355 draws neither an answer nor an ICMP port-unreachable, i.e. a
 * responder is listening and deliberately not talking to us.
 *
 * <p>Not applied to NetBIOS (designed to work across subnets via WINS, and in
 * practice the only source that answers a remote site at all) nor to SSDP
 * (UPnP defines a unicast M-SEARCH with no link-scope rule, and it is the only
 * source that names printers and NAS boxes).
 *
 * <p><b>Known limit:</b> "on-link" is judged from the hub's own interface
 * prefixes, because the target's netmask is unknowable from here. A WireGuard
 * interface configured with a very wide prefix (say {@code 10.0.0.0/8}) makes
 * everything inside it look on-link, and the gate lets those queries through.
 * That is the behaviour we had before this class existed, so such a setup loses
 * nothing — it just does not gain the saving.
 */
final class LinkScope {

    private static final Logger LOG = Logger.getLogger(LinkScope.class);

    /** One of the hub's own attached subnets. */
    private record Subnet(byte[] network, int prefixLength) {
        boolean contains(byte[] address) {
            if (address.length != network.length) return false;   // v4 vs v6
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) return false;
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) return true;
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Subnet> localSubnets;

    /** Snapshots the hub's attached subnets once, at construction — a scan
     *  builds one probe and walks hundreds of addresses with it, and the
     *  machine's interfaces do not move underneath a single sweep. */
    LinkScope() {
        this(enumerateLocalSubnets());
    }

    private LinkScope(List<Subnet> localSubnets) {
        this.localSubnets = localSubnets;
    }

    /** Test seam: build from CIDR strings instead of the machine's real
     *  interfaces, so the rule is testable without depending on the host. */
    static LinkScope of(List<String> cidrs) {
        List<Subnet> subnets = new ArrayList<>(cidrs.size());
        for (String cidr : cidrs) {
            int slash = cidr.indexOf('/');
            if (slash < 0) continue;
            try {
                subnets.add(new Subnet(
                        InetAddress.getByName(cidr.substring(0, slash)).getAddress(),
                        Integer.parseInt(cidr.substring(slash + 1))));
            } catch (Exception e) {
                // A malformed test/config CIDR narrows the gate, never widens it.
            }
        }
        return new LinkScope(List.copyOf(subnets));
    }

    /**
     * True when {@code ip} falls inside one of the hub's own attached subnets.
     *
     * <p>Fails <em>open</em> when the local subnets could not be determined at
     * all: with no knowledge, the old behaviour (try everything) is the safer
     * default — it costs time, where failing closed would cost names.
     */
    boolean isOnLink(String ip) {
        if (localSubnets.isEmpty()) return true;
        try {
            byte[] address = InetAddress.getByName(ip).getAddress();
            for (Subnet s : localSubnets) {
                if (s.contains(address)) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * True when {@code cidr} shares any address with one of the hub's own
     * attached subnets — the network-level form of {@link #isOnLink}, used to
     * tell an operator up front which name sources a scan of that network can
     * actually use (issue #79).
     *
     * <p>Containment is checked both ways: the hub may sit on a small subnet
     * inside a larger declared site network, or the site may be a subset of
     * what the hub is attached to. Either way they share addresses.
     *
     * <p>Fails <em>open</em> on unknown local subnets or a malformed CIDR, for
     * the same reason {@link #isOnLink} does — claiming a source will be tried
     * costs nothing if it then finds nothing, whereas wrongly reporting it off
     * would explain away a real result.
     */
    boolean overlaps(String cidr) {
        if (localSubnets.isEmpty()) return true;
        int slash = cidr.indexOf('/');
        if (slash < 0) return true;
        try {
            byte[] network = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
            int prefixLength = Integer.parseInt(cidr.substring(slash + 1));
            Subnet other = new Subnet(network, prefixLength);
            for (Subnet s : localSubnets) {
                if (s.contains(network) || other.contains(s.network())) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static List<Subnet> enumerateLocalSubnets() {
        List<Subnet> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (nic.isLoopback() || !nic.isUp()) continue;
                for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                    if (ia.getAddress() == null || ia.getNetworkPrefixLength() < 0) continue;
                    out.add(new Subnet(ia.getAddress().getAddress(), ia.getNetworkPrefixLength()));
                }
            }
        } catch (Exception e) {
            // Leaving the list empty makes isOnLink fail open — see its contract.
            LOG.debugf(e, "could not enumerate local subnets; link-scope gating disabled");
            return Collections.emptyList();
        }
        return List.copyOf(out);
    }
}
