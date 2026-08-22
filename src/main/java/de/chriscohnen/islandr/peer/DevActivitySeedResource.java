package de.chriscohnen.islandr.peer;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Dev-only endpoint: fills {@link PeerDailyActivity} with a plausible 30-day
 * history for every existing peer, since that table is otherwise only ever
 * written by {@link ActivityPoller} off real WireGuard handshakes. Used by
 * the Playwright screenshot script so the dashboard's connection-activity
 * heatmap has something to render instead of its empty state.
 * Not compiled into prod builds (@IfBuildProfile("dev")).
 */
@Path("/api/v1/dev/activity-seed")
@Produces(MediaType.APPLICATION_JSON)
@IfBuildProfile("dev")
public class DevActivitySeedResource {

    private static final int DAYS = 30;

    @POST
    @Transactional
    public Response seed() {
        List<Peer> allPeers = Peer.listAll();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Random rnd = new Random(42);
        int rows = 0;

        for (Peer p : allPeers) {
            // Gives each peer its own weekday-biased usage pattern instead of
            // uniform noise, so the heatmap actually shows a "pattern" —
            // e.g. one laptop connecting daily, another only on weekdays, a
            // rarely-used device with a multi-day gap.
            boolean weekdaysOnly = rnd.nextBoolean();
            double density = 0.5 + rnd.nextDouble() * 0.4;

            for (int i = 0; i < DAYS; i++) {
                LocalDate day = today.minusDays(i);
                if (weekdaysOnly && (day.getDayOfWeek().getValue() >= 6)) continue;
                if (rnd.nextDouble() > density) continue;

                // hits are 30s poll ticks (ActivityHeatmap.js: hours = hits/120,
                // absolute buckets up to a full ~2880/day) — a small 1-20 range
                // put every seeded day in the lightest bucket regardless of the
                // random draw, making the heatmap render as a flat single color.
                // Spread across roughly half an hour to ~20 connected hours so
                // the seed actually exercises all six intensity levels.
                double hoursConnected = 0.5 + rnd.nextDouble() * 20;
                int hits = (int) Math.round(hoursConnected * 120);
                long rx = (long) (rnd.nextDouble() * 200_000_000);
                long tx = (long) (rnd.nextDouble() * 40_000_000);

                PeerDailyActivity row = PeerDailyActivity.findById(new PeerDailyActivity.Id(p.id, day));
                if (row == null) {
                    row = new PeerDailyActivity(p.id, day);
                }
                row.sampleHits = hits;
                row.rxBytes = rx;
                row.txBytes = tx;
                row.persist();
                rows++;
            }
        }

        return Response.ok(Map.of("peers", allPeers.size(), "rows", rows)).build();
    }
}
