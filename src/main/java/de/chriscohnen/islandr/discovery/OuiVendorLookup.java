package de.chriscohnen.islandr.discovery;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * MAC prefix (OUI) -> vendor name, from the bundled IEEE MA-L registry
 * (issue #76). Loaded once, lazily, from the classpath resource at
 * {@code /data/oui-vendors.csv} — never a live fetch (air-gapped posture,
 * ADR-0010). Vendor is always derived at read time from this table; it is
 * never persisted anywhere (see {@code Resource.mac}), so refreshing the
 * bundled file later never leaves a stale vendor name behind.
 *
 * <p>Public — used both from this package ({@link DiscoveryResource}) and
 * from {@code acl.ResourceDto}, unlike {@link ArpCache}/{@link LinkScope}
 * which stay package-private.
 */
public final class OuiVendorLookup {

    private static final Logger LOG = Logger.getLogger(OuiVendorLookup.class);
    private static final String RESOURCE_PATH = "/data/oui-vendors.csv";
    private static volatile Map<String, String> table;

    private OuiVendorLookup() {}

    /** @param mac any MAC-shaped string (colons, hyphens, or none); null/short input -> empty. */
    public static Optional<String> vendorFor(String mac) {
        if (mac == null) return Optional.empty();
        String prefix = mac.replace(":", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (prefix.length() < 6) return Optional.empty();
        return Optional.ofNullable(table().get(prefix.substring(0, 6)));
    }

    private static Map<String, String> table() {
        Map<String, String> loaded = table;
        if (loaded != null) return loaded;
        synchronized (OuiVendorLookup.class) {
            if (table == null) table = load();
            return table;
        }
    }

    private static Map<String, String> load() {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = OuiVendorLookup.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                LOG.warn("OUI vendor table not found on classpath at " + RESOURCE_PATH + " — vendor lookup disabled");
                return Map.of();
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int comma = line.indexOf(',');
                    if (comma < 6) continue;
                    String prefix = line.substring(0, comma).trim().toUpperCase(Locale.ROOT);
                    String vendor = unquote(line.substring(comma + 1).trim());
                    if (prefix.length() == 6 && !vendor.isEmpty()) map.put(prefix, vendor);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to load OUI vendor table", e);
            return Map.of();
        }
        return Map.copyOf(map);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }
}
