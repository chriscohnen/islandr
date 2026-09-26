package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An open port without a name is a number. The frontend carries a protocol ->
 * default-port map for prefilling a form, which points the wrong way; this
 * looks up the other direction, server-side and from a bundled table.
 */
class PortServiceLookupTest {

    @Test
    void namesTheObviousAdministrativePorts() {
        assertThat(PortServiceLookup.serviceFor(22, "tcp")).contains("SSH");
        assertThat(PortServiceLookup.serviceFor(3389, "tcp")).contains("RDP");
        assertThat(PortServiceLookup.serviceFor(445, "tcp")).contains("SMB");
    }

    /** IANA assigns per (port, transport), and so does this. A WireGuard hub
     *  listens on UDP; the same number on TCP means nothing. */
    @Test
    void transportIsPartOfTheKey() {
        assertThat(PortServiceLookup.serviceFor(51820, "udp")).contains("WireGuard");
        assertThat(PortServiceLookup.serviceFor(51820, "tcp")).isEmpty();
    }

    /** A ResourcePort may carry transport 'both' — it should still get a name
     *  when either side of the pair has one. */
    @Test
    void bothMatchesEitherSide() {
        assertThat(PortServiceLookup.serviceFor(53, "both")).contains("DNS");
        assertThat(PortServiceLookup.serviceFor(51820, "both")).contains("WireGuard");
    }

    @Test
    void unknownPortStaysUnnamed() {
        assertThat(PortServiceLookup.serviceFor(47111, "tcp")).isEmpty();
    }

    /** Garbage in must not throw — callers pass whatever is in the database. */
    @Test
    void rejectsNonsenseQuietly() {
        assertThat(PortServiceLookup.serviceFor(0, "tcp")).isEmpty();
        assertThat(PortServiceLookup.serviceFor(70000, "tcp")).isEmpty();
        assertThat(PortServiceLookup.serviceFor(22, null)).isEqualTo(Optional.empty());
        assertThat(PortServiceLookup.serviceFor(22, "sctp")).isEmpty();
    }

    /** The device web interfaces an admin actually meets — the whole point of
     *  bundling a curated table rather than the full IANA registry. Asserted
     *  exactly: this is a lookup table, so the value is the behaviour, and an
     *  exact match catches a silent edit that a substring check would not. */
    @Test
    void namesTheDeviceWebInterfacesThatAreJustNumbers() {
        assertThat(PortServiceLookup.serviceFor(8006, "tcp")).contains("Proxmox VE");
        assertThat(PortServiceLookup.serviceFor(10000, "tcp")).contains("Webmin");
        assertThat(PortServiceLookup.serviceFor(9100, "tcp")).contains("Printer RAW (JetDirect)");
    }
}
