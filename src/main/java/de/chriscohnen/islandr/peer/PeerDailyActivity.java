package de.chriscohnen.islandr.peer;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One row per peer per UTC day, incremented by {@link ActivityPoller} whenever it
 * observes a fresh handshake for that peer on that day. Backs the dashboard's
 * connection activity heatmap (#32). See migration V44.
 *
 * <p>{@code day} is stored as an ISO-8601 string, not a JDBC DATE column —
 * SQLite's driver (dev backend, ADR-0004) does not round-trip {@link LocalDate}
 * reliably through its native DATE type. Fixed-width YYYY-MM-DD sorts and
 * range-compares correctly as a string on both SQLite and Postgres.
 */
@Entity
@Table(name = "peer_daily_activity")
public class PeerDailyActivity extends PanacheEntityBase {

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "peer_id", nullable = false, length = 36)
        public String peerId;

        @Column(name = "day", nullable = false, length = 10)
        public String day;

        public Id() {}

        public Id(String peerId, LocalDate day) {
            this.peerId = peerId;
            this.day = day.toString();
        }

        public Id(String peerId, String day) {
            this.peerId = peerId;
            this.day = day;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id id)) return false;
            return Objects.equals(peerId, id.peerId) && Objects.equals(day, id.day);
        }

        @Override
        public int hashCode() {
            return Objects.hash(peerId, day);
        }
    }

    @EmbeddedId
    public Id id;

    @Column(name = "sample_hits", nullable = false)
    public int sampleHits;

    public PeerDailyActivity() {}

    public PeerDailyActivity(String peerId, LocalDate day) {
        this.id = new Id(peerId, day);
    }
}
