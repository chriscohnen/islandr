package de.chriscohnen.islandr.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HSTS is irreversible for a browser until it expires, so the positive cases
 * matter as much as the negative ones: it must appear where the operator's
 * certificate is trusted, and never where a click-through is the only way in.
 */
class HstsDecisionTest {

    private static HstsResponseFilter filter(boolean directTlsTrusted) {
        HstsResponseFilter f = new HstsResponseFilter();
        f.securityHeaders = new SecurityHeaders();
        f.securityHeaders.markDirectTls(directTlsTrusted);
        return f;
    }

    @Test
    void directTlsWithAManagedCertificate_carriesHsts() {
        assertThat(filter(true).applies(true, false)).isTrue();
    }

    @Test
    void directTlsWithPlaceholderOrSelfSignedCertificate_carriesNoHsts() {
        assertThat(filter(false).applies(true, false)).isFalse();
    }

    @Test
    void httpsThroughATrustedProxy_carriesHsts_whateverTheLocalCertificate() {
        assertThat(filter(false).applies(false, true)).isTrue();
        assertThat(filter(true).applies(false, true)).isTrue();
    }

    @Test
    void plainHttp_carriesNoHsts() {
        assertThat(filter(true).applies(false, false)).isFalse();
    }
}
