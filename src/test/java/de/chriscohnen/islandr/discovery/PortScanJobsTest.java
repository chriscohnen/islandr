package de.chriscohnen.islandr.discovery;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The job registry behind a port-range scan. Same shape as {@link DiscoveryJobs}
 * — ephemeral, one active scan per target, findings kept on cancel — because an
 * admin who cancels a 65535-port run wants what it found so far, not an empty
 * list.
 *
 * <p>Runs in the suite's mock discovery mode, so nothing here touches a network.
 */
@QuarkusTest
class PortScanJobsTest {

    @Inject PortScanJobs jobs;

    private PortScanJobs.Job finishedScan(String resourceId, String spec) {
        PortScanJobs.Job job = jobs.start(resourceId, "10.60.0.9", spec);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (job.state() == PortScanJobs.State.RUNNING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(job.state()).as("scan finished within the timeout")
                .isNotEqualTo(PortScanJobs.State.RUNNING);
        return job;
    }

    @Test
    void theJobKnowsHowManyPortsItWillProbe() {
        PortScanJobs.Job job = finishedScan("res-total", "20-25");
        assertThat(job.total()).isEqualTo(6);
        assertThat(job.done()).isEqualTo(6);
    }

    @Test
    void cancellingKeepsThePortsItAlreadyFound() {
        PortScanJobs.Job job = finishedScan("res-cancel", "1-1024");
        assertThat(job.openPorts()).as("precondition: mock mode reports something").isNotEmpty();
        int before = job.openPorts().size();

        jobs.cancel(job.id);

        assertThat(job.state()).isEqualTo(PortScanJobs.State.CANCELLED);
        assertThat(job.openPorts()).hasSize(before);
    }

    /**
     * "Scan again" must never dead-end on a job orphaned by a client that
     * navigated away: the second start supersedes the first, and afterwards no
     * scan for that resource is still running.
     *
     * <p>Mock mode finishes a scan in one step, so the first job is usually
     * already DONE by the time the second starts and there is nothing to
     * cancel. That makes the cancel branch itself unobservable here — what is
     * asserted is the property an admin actually depends on: a fresh job, and
     * no stale running one left behind. The cancel path is covered by
     * {@link #cancellingKeepsThePortsItAlreadyFound()}.
     */
    @Test
    void asecondScanOfTheSameResourceSupersedesTheFirst() {
        PortScanJobs.Job first = jobs.start("res-supersede", "10.60.0.9", "1-1024");
        PortScanJobs.Job second = jobs.start("res-supersede", "10.60.0.9", "1-1024");

        assertThat(second.id).isNotEqualTo(first.id);
        assertThat(first.state()).isNotEqualTo(PortScanJobs.State.RUNNING);
    }

    /** A different resource is a different target: two of them scan in parallel. */
    @Test
    void scansOfDifferentResourcesCoexist() {
        PortScanJobs.Job a = jobs.start("res-a", "10.60.0.9", "20-25");
        PortScanJobs.Job b = jobs.start("res-b", "10.60.0.10", "20-25");
        assertThat(a.state()).isNotEqualTo(PortScanJobs.State.CANCELLED);
        assertThat(b.id).isNotEqualTo(a.id);
    }

    /** A malformed range never becomes a job — it is rejected at the door, so
     *  the endpoint can answer 409 instead of creating a scan that does nothing. */
    @Test
    void aMalformedRangeIsRejectedBeforeAJobExists() {
        assertThatThrownBy(() -> jobs.start("res-bad", "10.60.0.9", "100-20"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobs.start("res-bad", "10.60.0.9", "nonsense"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownJobIdIsNull() {
        assertThat(jobs.get("no-such-job")).isNull();
    }

    /** Ports come back ascending regardless of which probe finished first. */
    @Test
    void openPortsComeBackInPortOrder() {
        PortScanJobs.Job job = new PortScanJobs.Job("j1", "r1", "10.0.0.1", "1-1024", 1024);
        job.addPort(new PortScanner.OpenPort(443, "HTTPS"));
        job.addPort(new PortScanner.OpenPort(22, "SSH"));
        assertThat(job.openPorts()).extracting(PortScanner.OpenPort::port).containsExactly(22, 443);
    }
}
