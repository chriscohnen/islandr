package de.chriscohnen.islandr.firewall;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #84 / ADR-0031: a hub whose Islandr fails to start after a reboot used
 * to forward unfiltered while looking like it worked. The boot table covers
 * that window — and the handover out of it is the part that can go wrong.
 *
 * <p>The ordering is the whole safety property: the boot table is removed only
 * <em>after</em> Islandr's own is applied, so there is never a moment with
 * neither. And a failed removal must not be forgotten: both tables filtering
 * means a drop in either wins and every granted flow stays blocked (R-194).
 */
@QuarkusTest
class BootTableHandoverTest {

    @Inject RulesetService rulesets;
    @Inject NftablesAdapter adapter;
    @Inject BootTableState bootTable;

    /** CDI hands out a client proxy; a direct cast fails (see FirewallTest). */
    private MockNftablesAdapter mock() {
        return (MockNftablesAdapter) io.quarkus.arc.ClientProxy.unwrap(adapter);
    }

    @BeforeEach
    void reset() {
        adapter.resetForTests();
        bootTable.record(NftablesAdapter.BootTableHandover.FAILED);   // force "needs handover"
    }

    @Test
    void theBootTableIsHandedOverAfterASuccessfulApply() {
        mock().bootTableResult = NftablesAdapter.BootTableHandover.REMOVED;
        rulesets.recomputeAndApply("test:handover");

        assertThat(mock().bootTableRemovalCount).isEqualTo(1);
        assertThat(bootTable.handover()).isEqualTo(BootTableState.Handover.DONE);
        assertThat(bootTable.stillFiltering()).isFalse();
    }

    /** Once handed over, later applies must not keep shelling out to nft. */
    @Test
    void aCompletedHandoverIsNotRetried() {
        mock().bootTableResult = NftablesAdapter.BootTableHandover.REMOVED;
        rulesets.recomputeAndApply("test:handover");
        rulesets.recomputeAndApply("test:handover");

        assertThat(mock().bootTableRemovalCount).isEqualTo(1);
    }

    /**
     * A failed removal leaves both tables live — everything stays blocked — so
     * the next successful apply has to try again rather than wait for a reboot.
     */
    @Test
    void aFailedHandoverIsRetriedAndStaysVisible() {
        mock().bootTableResult = NftablesAdapter.BootTableHandover.FAILED;
        rulesets.recomputeAndApply("test:handover");
        assertThat(bootTable.handover()).isEqualTo(BootTableState.Handover.FAILED);
        assertThat(bootTable.stillFiltering()).isTrue();

        mock().bootTableResult = NftablesAdapter.BootTableHandover.REMOVED;
        rulesets.recomputeAndApply("test:handover");
        assertThat(mock().bootTableRemovalCount).isEqualTo(2);
        assertThat(bootTable.handover()).isEqualTo(BootTableState.Handover.DONE);
    }

    /** Nothing is removed when nothing was applied. */
    @Test
    void aFailedApplyDoesNotTouchTheBootTable() {
        mock().forceFailure = "mock: rejected";
        rulesets.recomputeAndApply("test:handover");

        assertThat(mock().bootTableRemovalCount).isZero();
        assertThat(bootTable.handover()).isEqualTo(BootTableState.Handover.FAILED);

        mock().forceFailure = null;
    }

    /** An install without the unit reports "nothing to hand over", not a failure. */
    @Test
    void anInstallWithoutABootTableIsNotAFailure() {
        mock().bootTableResult = NftablesAdapter.BootTableHandover.ABSENT;
        rulesets.recomputeAndApply("test:handover");

        assertThat(bootTable.handover()).isEqualTo(BootTableState.Handover.NOT_INSTALLED);
        assertThat(bootTable.stillFiltering()).isFalse();
    }
}
