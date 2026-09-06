package de.chriscohnen.islandr.discovery;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the kernel's own ARP neighbor table to answer "what's this on-link
 * IP's MAC address" without any privilege — a TCP {@code connect()} to an
 * on-link host already makes the kernel populate this table as a side
 * effect (same unprivileged posture as ADR-0011/0014, issue #76).
 *
 * <p>Linux-only: {@code /proc/net/arp} is a plain-text, world-readable file.
 * Any other platform (macOS dev boxes, CI) has no such file — {@link #lookup}
 * simply returns empty, the same graceful-degrade the hub-load widget
 * already uses for its own {@code /proc} reads.
 *
 * <p>Callers must gate this by {@link LinkScope#isOnLink} first — this class
 * only reads whatever is in the table, it doesn't know or care about scope.
 */
final class ArpCache {

    private static final Path DEFAULT_ARP_TABLE = Path.of("/proc/net/arp");
    private static final String NULL_MAC = "00:00:00:00:00:00";

    private final Path arpTable;

    ArpCache() {
        this(DEFAULT_ARP_TABLE);
    }

    /** Test-only entry point — points at a fixture file instead of the real /proc path. */
    ArpCache(Path arpTable) {
        this.arpTable = arpTable;
    }

    /** Whether the kernel's ARP table can be read at all — false on any
     *  non-Linux host, where {@code /proc/net/arp} simply does not exist. */
    boolean available() {
        return Files.isReadable(arpTable);
    }

    Optional<String> lookup(String ip) {
        if (!Files.isReadable(arpTable)) return Optional.empty();
        try (BufferedReader r = Files.newBufferedReader(arpTable)) {
            r.readLine(); // header row
            String line;
            while ((line = r.readLine()) != null) {
                // IP address / HW type / Flags / HW address / Mask / Device
                String[] cols = line.trim().split("\\s+");
                if (cols.length < 4 || !cols[0].equals(ip)) continue;
                String mac = cols[3].toLowerCase(Locale.ROOT);
                return mac.equals(NULL_MAC) ? Optional.empty() : Optional.of(mac);
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
