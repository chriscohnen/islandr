package de.chriscohnen.islandr.firewall;

import de.chriscohnen.islandr.settings.Settings;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The hub's WAN interface — where traffic to an external site leaves the host,
 * masqueraded to the hub's own address (egress-via-hub, 1.4.0).
 *
 * <p>An override in the settings wins; otherwise the interface of the default
 * route with the lowest metric, read from {@code /proc/net/route}. Detected at
 * every ruleset build rather than once at boot, so a hub that gets its uplink
 * after Islandr started still masquerades out of the right interface.
 *
 * <p>The name goes into nftables text verbatim, so whatever comes back — from
 * the kernel or from an admin — must match {@link #NAME}, Linux's own rule
 * for interface names (at most 15 bytes, no quotes, no whitespace). Anything
 * else counts as unknown, and unknown means no masquerade rule at all.
 */
@ApplicationScoped
public class WanInterface {

    private static final Logger LOG = Logger.getLogger(WanInterface.class);

    /** IFNAMSIZ is 16 including the NUL terminator. */
    public static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,15}$");

    static final Path PROC_NET_ROUTE = Path.of("/proc/net/route");

    /** The interface to masquerade out of, or null when none is known. */
    public String current() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        if (s != null && s.wanInterface != null && !s.wanInterface.isBlank()) {
            return valid(s.wanInterface.trim());
        }
        return detected();
    }

    /** The interface of the default route, or null when there is none to read. */
    public String detected() {
        if (!Files.isReadable(PROC_NET_ROUTE)) return null;
        try {
            return valid(parseDefaultRoute(Files.readString(PROC_NET_ROUTE)));
        } catch (IOException e) {
            LOG.warnf("cannot read %s: %s", PROC_NET_ROUTE, e.getMessage());
            return null;
        }
    }

    /**
     * The interface of the IPv4 default route (Destination and Mask both
     * {@code 00000000}) with the lowest metric. Null when the table has none.
     */
    public static String parseDefaultRoute(String procNetRoute) {
        if (procNetRoute == null) return null;
        String best = null;
        long bestMetric = Long.MAX_VALUE;
        for (String line : procNetRoute.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 8 || "Iface".equals(f[0])) continue;
            if (!"00000000".equals(f[1]) || !"00000000".equals(f[7])) continue;
            long metric;
            try {
                metric = Long.parseLong(f[6]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (metric < bestMetric) {
                bestMetric = metric;
                best = f[0];
            }
        }
        return best;
    }

    private static String valid(String name) {
        return name != null && NAME.matcher(name).matches() ? name : null;
    }
}
