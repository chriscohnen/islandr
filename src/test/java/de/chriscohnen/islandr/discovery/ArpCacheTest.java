package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the unprivileged ARP-table reader (issue #76) — a fixture
 *  file shaped like the real /proc/net/arp, so no actual Linux /proc access
 *  is needed to test the parsing logic. */
class ArpCacheTest {

    private static final String FIXTURE =
            "IP address       HW type     Flags       HW address            Mask     Device\n"
          + "192.168.1.1      0x1         0x2         aa:bb:cc:dd:ee:ff     *        eth0\n"
          + "192.168.1.2      0x1         0x0         00:00:00:00:00:00     *        eth0\n";

    private Path fixtureFile(Path tmp) throws IOException {
        Path arpFile = tmp.resolve("arp");
        Files.writeString(arpFile, FIXTURE);
        return arpFile;
    }

    @Test
    void lookup_returnsMac_forKnownIp(@TempDir Path tmp) throws IOException {
        ArpCache cache = new ArpCache(fixtureFile(tmp));

        assertThat(cache.lookup("192.168.1.1")).contains("aa:bb:cc:dd:ee:ff");
    }

    @Test
    void lookup_returnsEmpty_forUnknownIp(@TempDir Path tmp) throws IOException {
        ArpCache cache = new ArpCache(fixtureFile(tmp));

        assertThat(cache.lookup("192.168.1.99")).isEmpty();
    }

    @Test
    void lookup_returnsEmpty_forIncompleteArpEntry_nullMac(@TempDir Path tmp) throws IOException {
        ArpCache cache = new ArpCache(fixtureFile(tmp));

        // Flags 0x0 / MAC 00:00:00:00:00:00 — an incomplete/stale ARP entry, not a real answer.
        assertThat(cache.lookup("192.168.1.2")).isEmpty();
    }

    @Test
    void lookup_returnsEmpty_whenArpFileMissing(@TempDir Path tmp) {
        ArpCache cache = new ArpCache(tmp.resolve("does-not-exist"));

        assertThat(cache.lookup("192.168.1.1")).isEmpty();
    }
}
