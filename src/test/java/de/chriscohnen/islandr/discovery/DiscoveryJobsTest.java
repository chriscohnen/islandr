package de.chriscohnen.islandr.discovery;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #75: hosts belong to the job as they are found, not only once the sweep
 * is over — so a cancelled scan keeps what it already discovered instead of
 * throwing the work away.
 *
 * <p>Runs in the suite's mock discovery mode, which completes in one step. That
 * makes "visible mid-flight" untestable here; {@link DiscoveryScannerTest}
 * covers that property directly with a blocked probe. What this asserts is the
 * consequence an operator actually sees: cancelling does not empty the list.
 */
@QuarkusTest
class DiscoveryJobsTest {

    @Inject DiscoveryJobs jobs;

    private DiscoveryJobs.Job finishedScan(String siteId) {
        DiscoveryJobs.Job job = jobs.start(siteId, "10.60.0.0/29", null);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (job.state() == DiscoveryJobs.State.RUNNING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(job.state()).as("scan finished within the timeout")
                .isNotEqualTo(DiscoveryJobs.State.RUNNING);
        return job;
    }

    @Test
    void cancellingAScanKeepsTheHostsItAlreadyFound() {
        DiscoveryJobs.Job job = finishedScan("site-cancel-keeps");
        assertThat(job.hosts()).as("precondition: the scan found something").isNotEmpty();
        int foundBeforeCancel = job.hosts().size();

        jobs.cancel(job.id);

        assertThat(job.state()).isEqualTo(DiscoveryJobs.State.CANCELLED);
        assertThat(job.hosts()).hasSize(foundBeforeCancel);
    }

    @Test
    void hostsComeBackInNumericIpOrder_notInTheOrderTheyWereFound() {
        // Probes finish in completion order, so a fast host high up the range
        // lands before a slow one low down. Ordering here rather than in each
        // consumer keeps every reader stable and the rows from reshuffling
        // under the pointer while a scan is still adding to them.
        DiscoveryJobs.Job job = new DiscoveryJobs.Job("j1", "s1", "10.0.0.0/24", 254);
        job.addHost(new DiscoveryScanner.DiscoveredHost("10.0.0.200", java.util.List.of(80), "computer", null, null));
        job.addHost(new DiscoveryScanner.DiscoveredHost("10.0.0.3", java.util.List.of(80), "computer", null, null));
        job.addHost(new DiscoveryScanner.DiscoveredHost("10.0.0.30", java.util.List.of(80), "computer", null, null));

        assertThat(job.hosts()).extracting(DiscoveryScanner.DiscoveredHost::ip)
                .containsExactly("10.0.0.3", "10.0.0.30", "10.0.0.200");
    }

    @Test
    void theFoundCountMatchesTheHostsOnTheJob() {
        // The counter and the list are two views of the same thing; if they can
        // disagree, the dialog says "6 found" above five rows.
        DiscoveryJobs.Job job = finishedScan("site-count-matches");

        assertThat(job.found()).isEqualTo(job.hosts().size());
    }
}
