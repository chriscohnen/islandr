package de.chriscohnen.islandr.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The port-scan range is free text an admin types, and it is the one input that
 * decides how many connect() attempts leave the hub. Every rejection here is a
 * scan that never runs, which is the point.
 */
class PortRangeTest {

    @Test
    void parsesASingleRange() {
        assertThat(PortRange.parse("20-25")).containsExactly(20, 21, 22, 23, 24, 25);
    }

    @Test
    void parsesASinglePort() {
        assertThat(PortRange.parse("8006")).containsExactly(8006);
    }

    /** Comma-separated parts, because the default itself is a union: a range
     *  plus the service ports that sit above it. */
    @Test
    void parsesAListOfRangesAndPorts() {
        assertThat(PortRange.parse("22, 80, 8000-8002")).containsExactly(22, 80, 8000, 8001, 8002);
    }

    /** A port named twice is scanned once — otherwise "1-100,22" would probe 22
     *  twice and report it twice. */
    @Test
    void deduplicatesAndSorts() {
        assertThat(PortRange.parse("443,22,443,20-22")).containsExactly(20, 21, 22, 443);
    }

    @Test
    void whitespaceAndEmptyPartsAreTolerated() {
        assertThat(PortRange.parse("  22 , , 80  ")).containsExactly(22, 80);
    }

    /** A reversed range is a typo, not an empty set: silently scanning nothing
     *  would report "no open ports" for a range that was never probed. */
    @Test
    void rejectsAReversedRange() {
        assertThatThrownBy(() -> PortRange.parse("100-20"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100-20");
    }

    @Test
    void rejectsPortZeroAndAbove65535() {
        assertThatThrownBy(() -> PortRange.parse("0-10")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PortRange.parse("65530-70000")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonNumericAndEmptyInput() {
        assertThatThrownBy(() -> PortRange.parse("http")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PortRange.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PortRange.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** The whole port space is allowed — it just has to be typed. */
    @Test
    void allowsTheFullSpaceWhenAskedForExplicitly() {
        assertThat(PortRange.parse("1-65535")).hasSize(65535);
    }

    /**
     * The default is deliberately not 1-65535: the big run should be a
     * deliberate entry, not a button someone hits by accident. It is the
     * well-known range plus the service ports above it that a device is
     * actually likely to answer on.
     */
    @Test
    void theDefaultIsTheWellKnownRangePlusKnownServicePorts() {
        List<Integer> def = PortRange.parse(PortRange.DEFAULT_SPEC);
        assertThat(def).contains(22, 80, 443);          // inside 1-1024
        assertThat(def).contains(3389, 8006, 8123);     // above it, from the service table
        assertThat(def).doesNotContain(65000);
        assertThat(def.size()).isLessThan(2000);
    }

    /** Sorted ascending, so results arrive in an order a reader expects. */
    @Test
    void theDefaultIsSorted() {
        assertThat(PortRange.parse(PortRange.DEFAULT_SPEC)).isSorted();
    }
}
