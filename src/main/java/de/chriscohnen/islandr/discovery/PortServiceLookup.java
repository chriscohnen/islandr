package de.chriscohnen.islandr.discovery;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Port number -> service name, from a table bundled in the binary. Same shape
 * as {@link OuiVendorLookup}: loaded once, lazily, from the classpath and
 * never over the network — the air-gapped posture (ADR-0010) is a promise, not
 * a default, and a name lookup is exactly the kind of convenience that
 * quietly breaks it.
 *
 * <p>The frontend already carries a protocol-to-default-port map for
 * prefilling the resource form. That points the other way: it answers "which
 * port does RDP use", not "what is listening on 8006". Only the second
 * question turns a port list into something readable, and only the server can
 * answer it for callers that are not the console.
 *
 * <p><b>Keyed by (port, transport), because IANA is.</b> 514/udp is syslog and
 * 514/tcp is the rsh shell; naming either from the number alone would be
 * wrong half the time. A {@code ResourcePort} may also carry {@code both},
 * which matches whichever side has a name.
 *
 * <p><b>Curated, not complete, and that is the design.</b> The IANA registry
 * holds some 14,000 assignments, most of them for protocols nobody has run
 * this decade, and many ports carry two or three historical claims. What
 * earns its place here is what an administrator actually meets on a network:
 * the standard services, the NAS and printer ports, and the device web
 * interfaces that are otherwise just numbers — 8006, 8123, 9443, 10000. A
 * port with no entry keeps its number, which is no worse than today.
 *
 * <p>Where two well-known services genuinely share a port, the entry says so
 * ({@code Cockpit / Prometheus}) rather than picking a winner and being
 * confidently wrong for half the readers.
 */
public final class PortServiceLookup {

    private static final Logger LOG = Logger.getLogger(PortServiceLookup.class);
    private static final String RESOURCE_PATH = "/data/port-services.csv";
    private static volatile Map<String, String> table;

    private PortServiceLookup() {}

    /**
     * @param port      1-65535; anything else yields empty
     * @param transport {@code tcp}, {@code udp} or {@code both}; null or
     *                  anything else yields empty
     * @return the service name, or empty when the table has no entry
     */
    public static Optional<String> serviceFor(int port, String transport) {
        if (port < 1 || port > 65535 || transport == null) return Optional.empty();
        String t = transport.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "tcp", "udp" -> Optional.ofNullable(table().get(key(port, t)));
            // Either side counts. TCP first only because the ports an admin
            // looks up are more often TCP — where both sides carry a name they
            // are the same name anyway (53 is DNS on either).
            case "both" -> Optional.ofNullable(table().get(key(port, "tcp")))
                    .or(() -> Optional.ofNullable(table().get(key(port, "udp"))));
            default -> Optional.empty();
        };
    }

    /**
     * Every TCP port the table carries a name for, ascending. The port-range
     * scan's default is built from this: the well-known range plus the ports
     * above it a device is actually likely to answer on, rather than the whole
     * 65535 (see {@link PortRange#DEFAULT_SPEC}).
     */
    public static List<Integer> knownTcpPorts() {
        return table().keySet().stream()
                .filter(k -> k.endsWith("/tcp"))
                .map(k -> Integer.parseInt(k.substring(0, k.indexOf('/'))))
                .sorted()
                .toList();
    }

    private static String key(int port, String transport) {
        return port + "/" + transport;
    }

    private static Map<String, String> table() {
        Map<String, String> loaded = table;
        if (loaded != null) return loaded;
        synchronized (PortServiceLookup.class) {
            if (table == null) table = load();
            return table;
        }
    }

    private static Map<String, String> load() {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = PortServiceLookup.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                LOG.warn("Port service table not found on classpath at " + RESOURCE_PATH
                        + " — ports stay unnamed");
                return Map.of();
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] parts = line.split(",", 3);
                    if (parts.length < 3) continue;
                    String service = parts[2].trim();
                    if (service.isEmpty()) continue;
                    try {
                        int port = Integer.parseInt(parts[0].trim());
                        String transport = parts[1].trim().toLowerCase(Locale.ROOT);
                        if (port < 1 || port > 65535) continue;
                        if (!transport.equals("tcp") && !transport.equals("udp")) continue;
                        map.put(key(port, transport), service);
                    } catch (NumberFormatException ignored) {
                        // A malformed row costs one name, not the whole table.
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to load port service table", e);
            return Map.of();
        }
        return Map.copyOf(map);
    }
}
