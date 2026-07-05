package de.chriscohnen.islandr.proxy;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EnforcementStatus} (design §3, D2): the in-memory,
 * single-source-of-truth for whether the host enforcement plane is reachable.
 * Updated by the degraded call-sites and the reconciler, read by the REST
 * endpoint. A fixed {@link Clock} makes the timestamps assertable.
 */
class EnforcementStatusTest {

    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    /**
     * Default is ACTIVE: in {@code real}/{@code mock} mode enforcement is direct,
     * so the banner stays hidden until socket-mode boot proves otherwise (design §7).
     */
    @Test
    void initialState_isActive() {
        EnforcementStatus status = new EnforcementStatus(clock);

        assertThat(status.state()).isEqualTo(EnforcementStatus.State.ACTIVE);
        assertThat(status.lastError()).isNull();
    }

    /** BR-027/028: proxy unreachable → UNAVAILABLE, with the reason and a probe timestamp. */
    @Test
    void markUnavailable_setsStateErrorAndProbeTime() {
        EnforcementStatus status = new EnforcementStatus(clock);

        status.markUnavailable("proxy socket absent");

        assertThat(status.state()).isEqualTo(EnforcementStatus.State.UNAVAILABLE);
        assertThat(status.lastError()).isEqualTo("proxy socket absent");
        assertThat(status.lastProbeAt()).isEqualTo(NOW);
    }

    /** Reconcile-on-connect enters RECONCILING while the full re-apply runs (design §6). */
    @Test
    void markReconciling_setsStateAndProbeTime() {
        EnforcementStatus status = new EnforcementStatus(clock);

        status.markReconciling();

        assertThat(status.state()).isEqualTo(EnforcementStatus.State.RECONCILING);
        assertThat(status.lastProbeAt()).isEqualTo(NOW);
    }

    /** A successful reconcile clears the error and records when enforcement was last applied. */
    @Test
    void markActive_clearsErrorAndRecordsReconcileTime() {
        EnforcementStatus status = new EnforcementStatus(clock);
        status.markUnavailable("was down");

        status.markActive();

        assertThat(status.state()).isEqualTo(EnforcementStatus.State.ACTIVE);
        assertThat(status.lastError()).isNull();
        assertThat(status.lastReconcileAt()).isEqualTo(NOW);
    }
}
