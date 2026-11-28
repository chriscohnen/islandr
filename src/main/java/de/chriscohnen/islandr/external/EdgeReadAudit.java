package de.chriscohnen.islandr.external;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An edge agent polls every few seconds; writing an audit entry per poll buries everything else in
 * the log. What is worth recording is that the edge was <em>handed something different</em>: a new
 * set of peers or preshared keys. This remembers, per edge and kind of read, a fingerprint of what
 * was last handed out and says whether the current answer differs. In memory only — after a restart
 * the first read is recorded again, which is the point: a restart is a fresh start for the log.
 */
@ApplicationScoped
public class EdgeReadAudit {

    private final ConcurrentHashMap<String, String> last = new ConcurrentHashMap<>();

    /** True when {@code fingerprint} differs from the last one seen for this edge and read. */
    public boolean changed(String edgeId, String read, String fingerprint) {
        return !fingerprint.equals(last.put(edgeId + "|" + read, fingerprint));
    }

    /** A stable digest of the given lines (order-independent); never the lines themselves. */
    public static String fingerprint(Collection<String> lines) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String l : List.copyOf(lines).stream().sorted().toList()) {
                md.update(l.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
