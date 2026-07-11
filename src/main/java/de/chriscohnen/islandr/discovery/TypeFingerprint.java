package de.chriscohnen.islandr.discovery;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guesses a resource type from a host's open TCP ports (ADR-0014, §5). Only the
 * fingerprinting ports drive a type; the "liveness-only" web ports prove the host
 * is up but are too generic to name a type, so they yield {@code computer} only as
 * a last resort. A host with no probed port open (ICMP-only) is {@code unknown}.
 *
 * <p>The guess only pre-fills the import review — the admin overrides it — so a
 * deterministic "first matching rule wins" is enough. Fingerprint results are
 * valid {@code Resource} types; {@code unknown} is a review-only sentinel the admin
 * must resolve before import.
 */
public final class TypeFingerprint {

    /** Review-only value when nothing could be fingerprinted; not a Resource type. */
    public static final String UNKNOWN = "unknown";

    private TypeFingerprint() {}

    /** Fingerprint ports → type, in order; the first open one wins (ADR-0014 §5). */
    private static final List<Map.Entry<Integer, String>> RULES = List.of(
            Map.entry(554,  "camera"),      // RTSP
            Map.entry(9100, "printer"),     // JetDirect / raw print
            Map.entry(631,  "printer"),     // IPP
            Map.entry(8006, "rackserver"),  // Proxmox VE
            Map.entry(3389, "computer"),    // RDP — also covers "445 with 3389 → computer"
            Map.entry(5900, "computer"),    // VNC
            Map.entry(22,   "computer"),    // SSH
            Map.entry(445,  "nas")          // SMB without a remote shell → likely a NAS
    );

    /** Reachable but too generic to type on their own (ADR-0014 §5). */
    private static final Set<Integer> LIVENESS_WEB = Set.of(80, 443, 8080, 8123, 8443);

    public static String guess(Collection<Integer> openPorts) {
        Set<Integer> open = new HashSet<>(openPorts);
        for (Map.Entry<Integer, String> rule : RULES) {
            if (open.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        // No fingerprint port: a web port still means "a computer is up"; otherwise unknown.
        for (int port : open) {
            if (LIVENESS_WEB.contains(port)) {
                return "computer";
            }
        }
        return UNKNOWN;
    }
}
