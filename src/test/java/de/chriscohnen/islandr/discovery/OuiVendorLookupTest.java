package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests against the real bundled table (Task 1) — no fixture file,
 *  since the whole point is confirming the shipped data loads and a few
 *  well-known prefixes resolve. */
class OuiVendorLookupTest {

    @Test
    void vendorFor_resolvesAKnownPrefix_caseAndSeparatorInsensitive() {
        assertThat(OuiVendorLookup.vendorFor("b8:27:eb:00:11:22")).contains("Raspberry Pi Foundation");
        assertThat(OuiVendorLookup.vendorFor("B8-27-EB-00-11-22")).contains("Raspberry Pi Foundation");
        assertThat(OuiVendorLookup.vendorFor("b827eb001122")).contains("Raspberry Pi Foundation");
    }

    @Test
    void vendorFor_returnsEmpty_forUnknownPrefix() {
        assertThat(OuiVendorLookup.vendorFor("FF:FF:FF:00:11:22")).isEmpty();
    }

    @Test
    void vendorFor_returnsEmpty_forNullOrShortInput() {
        assertThat(OuiVendorLookup.vendorFor(null)).isEmpty();
        assertThat(OuiVendorLookup.vendorFor("aa:bb")).isEmpty();
    }
}
