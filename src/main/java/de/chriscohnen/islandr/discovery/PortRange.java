package de.chriscohnen.islandr.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Parses the port specification an admin types for a port-range scan —
 * {@code "1-1024"}, {@code "8006"}, {@code "22,80,8000-8100"} — into the exact
 * list of TCP ports that will be probed.
 *
 * <p>This is the only place that decides how many {@code connect()} attempts a
 * scan makes, so it rejects rather than interprets. A reversed range is a typo,
 * not an empty set: scanning nothing and reporting "no open ports" would be a
 * wrong answer to a question that was never asked. Port 0 is excluded because it
 * is not a port an admin can mean here — {@code port = 0} means "all ports" in
 * {@link de.chriscohnen.islandr.acl.ResourcePort}, which is a different concept
 * that has nothing to do with probing.
 *
 * <p>The full space is allowed. It just has to be typed: see {@link #DEFAULT_SPEC}.
 */
public final class PortRange {

    /** Hard ceiling; 1-65535 parses, anything claiming more does not. */
    public static final int MAX_PORTS = 65535;

    /**
     * What the scan dialog starts with. Deliberately <em>not</em> {@code 1-65535}:
     * the big run should be a deliberate entry rather than a button someone hits
     * by accident. The well-known range covers the services that sit low; the
     * named ports above it — 3389, 8006, 8123, 9443 — are where device consoles
     * actually live, and a bare {@code 1-1024} would miss every one of them.
     */
    public static final String DEFAULT_SPEC = "1-1024,+known";

    /** Token inside a spec that expands to every named TCP port above 1024. */
    private static final String KNOWN = "+known";

    private PortRange() {}

    /**
     * @param spec comma-separated ports and {@code from-to} ranges
     * @return ascending, duplicate-free port list
     * @throws IllegalArgumentException the spec is empty, malformed, out of
     *         range, or names a reversed range
     */
    public static List<Integer> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("port range must not be empty");
        }
        // TreeSet, not a List: a port named twice ("1-100,22") is probed once,
        // and the ascending order is what every reader downstream expects.
        TreeSet<Integer> ports = new TreeSet<>();
        for (String rawPart : spec.split(",", -1)) {
            String part = rawPart.trim();
            if (part.isEmpty()) continue;   // "22,,80" and a trailing comma are harmless
            if (part.equalsIgnoreCase(KNOWN)) {
                ports.addAll(PortServiceLookup.knownTcpPorts());
                continue;
            }
            int dash = part.indexOf('-');
            if (dash < 0) {
                ports.add(requirePort(part, spec));
            } else {
                int from = requirePort(part.substring(0, dash).trim(), spec);
                int to = requirePort(part.substring(dash + 1).trim(), spec);
                if (to < from) {
                    throw new IllegalArgumentException("range runs backwards: " + part);
                }
                for (int p = from; p <= to; p++) ports.add(p);
            }
        }
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("port range names no ports: " + spec);
        }
        return List.copyOf(new ArrayList<>(ports));
    }

    /** How many ports a spec would probe, without building the list. */
    public static int count(String spec) {
        return parse(spec).size();
    }

    private static int requirePort(String token, String spec) {
        int value;
        try {
            value = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a port number: '" + token + "' in '" + spec + "'");
        }
        if (value < 1 || value > MAX_PORTS) {
            throw new IllegalArgumentException("port out of range (1-" + MAX_PORTS + "): " + value);
        }
        return value;
    }
}
