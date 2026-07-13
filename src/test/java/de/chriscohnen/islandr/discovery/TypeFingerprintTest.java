package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the port→type guess, per the ADR-0014 §5 table (slice 2). */
class TypeFingerprintTest {

    @Test
    void rtsp_isCamera() {
        assertThat(TypeFingerprint.guess(List.of(554, 80))).isEqualTo("camera");
    }

    @Test
    void printPorts_arePrinter() {
        assertThat(TypeFingerprint.guess(List.of(9100))).isEqualTo("printer");
        assertThat(TypeFingerprint.guess(List.of(631, 80))).isEqualTo("printer");
    }

    @Test
    void proxmox_isRackserver() {
        assertThat(TypeFingerprint.guess(List.of(8006, 22))).isEqualTo("rackserver");
    }

    @Test
    void remoteShellPorts_areComputer() {
        assertThat(TypeFingerprint.guess(List.of(22))).isEqualTo("computer");
        assertThat(TypeFingerprint.guess(List.of(5900))).isEqualTo("computer");
    }

    @Test
    void smb_withoutRdp_isNas_withRdp_isComputer() {
        assertThat(TypeFingerprint.guess(List.of(445))).isEqualTo("nas");
        assertThat(TypeFingerprint.guess(List.of(445, 3389))).isEqualTo("computer");
    }

    @Test
    void livenessOnlyWebPorts_areComputerAsLastResort() {
        assertThat(TypeFingerprint.guess(List.of(80, 443, 8080, 8443))).isEqualTo("computer");
        assertThat(TypeFingerprint.guess(List.of(8123))).isEqualTo("computer"); // Home Assistant: no 'iot' guess
    }

    @Test
    void noProbedPortOpen_isUnknown() {
        assertThat(TypeFingerprint.guess(List.of())).isEqualTo(TypeFingerprint.UNKNOWN);
    }

    @Test
    void mostSpecificWins_cameraOverWeb() {
        assertThat(TypeFingerprint.guess(List.of(80, 443, 554))).isEqualTo("camera");
    }
}
