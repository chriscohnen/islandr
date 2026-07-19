# ADR-0016 — Daily-aggregated storage for the peer activity heatmap, not raw time series

**Status:** Proposed
**Date:** 2026-07-19
**Deciders:** Christian Cohnen
**Relates to:** [Issue #32](https://github.com/chriscohnen/islandr/issues/32) (the feature this backs), `peer/ActivityPoller.java` (the existing 30 s poller this extends), [ADR-0008](0008-runtime-settings-in-db.md) (runtime settings pattern the retention window follows)

## Context

The dashboard shows live/idle state and a per-peer last-seen timestamp, but no history. There is no way to answer "was this peer actually connected during its scheduled window" or "which peers haven't shown up in weeks" without history over time.

`ActivityPoller` (`peer/ActivityPoller.java`) already samples `wg show <iface> dump` every 30 s and writes `lastSeenAt` / `lastSeenEndpoint` / cumulative rx-tx bytes back onto the `Peer` row — one row per peer, overwritten every tick, no history retained by design (deliberately minimal in v1, per the class-level comment). Issue #32 asks for a peers-×-days activity matrix, colored like a GitHub contribution graph. That needs *some* form of history; the question this ADR answers is what shape that history takes in the database.

The two poles are: keep every sample forever (maximum fidelity, unbounded growth), or throw away the timestamp entirely and keep only a coarse daily bucket (bounded growth, coarse fidelity). The heatmap's actual consumption pattern — a colored cell per peer per day — never needs sub-day resolution to render, which is the lever this decision turns on.

## Decision

**Aggregate into one row per peer per day** — `peer_daily_activity(peer_id, day, sample_hits)` — upserted incrementally by the existing `ActivityPoller` tick (increment `sample_hits` for the current day when a peer reports a non-null handshake, same "seen" rule already used for `lastSeenAt`). No raw per-sample table. Retention is a configurable settings value (default 180 days, following the [ADR-0008](0008-runtime-settings-in-db.md) runtime-settings pattern), enforced by a daily cleanup job.

### Data size, worked

- Raw time series (baseline for comparison, not chosen): 1 row per peer per 30 s tick = 2,880 rows/peer/day = ~1.05M rows/peer/year. At 50 peers that's ~52M rows/year, growing without bound unless a separate downsampling/retention job is *also* built.
- Daily aggregate (the decision): 1 row per peer per day = 365 rows/peer/year. At 50 peers, ~18k rows/year. At 500 peers, ~180k rows/year. Three orders of magnitude smaller, and the row count is bounded purely by `peer_count × retention_days` — flat, not growing with poll frequency.
- Halving the poll interval (30 s → 15 s) doubles the raw-series cost for zero rendering benefit, but has zero effect on the aggregate's size — a second lever in the aggregate's favor as the poller's cadence is tuned over time.

### Effort

- The aggregate reuses the existing poller's tick and its "non-null handshake = seen" rule verbatim — it's one additional upsert inside `ActivityPoller.poll()`, no new scheduled job, no new adapter. A raw series would need the same upsert logic *plus* a separate retention/downsampling job to keep the table bounded, which is strictly more code for a benefit (sub-day resolution) the UI doesn't use.
- Rendering is a single `GROUP BY` -free read: peers × days already matches the table's own shape, so the query is a plain range-scan on `(peer_id, day)`, no aggregation step at render time. A raw series would need a `COUNT`/bucket-by-day query on every render (or a materialized rollup — which is this same aggregate table, built the hard way).

### Benefit trade-off

- What's given up: intra-day resolution (e.g. "connected 09:00–09:15, dropped, reconnected 14:00" collapses to "some activity that day"). The heatmap doesn't display that granularity regardless — a day-cell has one color. If a future feature genuinely needs intra-day resolution (e.g. an uptime percentage SLA view), that is a different feature with a different, deliberately separate storage decision — not a reason to pay the 1000x storage cost today for a consumer that doesn't exist yet.
- What's gained: bounded, predictable storage that needs no separate pruning strategy beyond the existing retention-window delete, and a render query that is already shaped like the answer.

## Alternatives considered (Pugh Matrix)

Baseline: **A — daily aggregate upserted by the existing poller** (the decision).

| Criterion (weight) | A: daily aggregate (baseline) | B: raw per-sample time series | C: embedded TSDB / RRD (rrdtool-style) | D: no persistence, render from live `wg show` only |
|---|:---:|:---:|:---:|:---:|
| Data size / bounded growth (5) | 0 | −1 | 0 | +1 |
| Implementation effort (4) | 0 | −1 | −1 | +1 |
| Matches the heatmap's actual render need (4) | 0 | 0 | 0 | −1 |
| No new component/dependency (3) | 0 | 0 | −1 | 0 |
| Query simplicity at render time (2) | 0 | −1 | 0 | 0 |
| Room for a future higher-resolution feature (1) | 0 | +1 | +1 | −1 |
| **Weighted total** | **0** | **−10** | **−7** | **+2\*** |

\* D's positive score is misleading: it scores well because it has almost no cost, but it cannot satisfy the requirement at all (see below) — it is disqualified on capability, not weighed down on cost.

- **B (raw per-sample series)** loses on the two highest-weighted criteria: it grows without bound unless a second job is built to prune/downsample it (at which point that job *produces* something equivalent to A, just later and with more moving parts), and the render query has to aggregate on every page load instead of reading pre-shaped rows. Its one edge — future intra-day resolution — is speculative; nothing in scope needs it.
- **C (embedded TSDB / RRD, matching the competitor pattern this repo was benchmarked against)** trades the growth problem for an extra container/process to run, back up, and version — a new operational component for a self-hosted single-binary product where "no extra containers" is a deliberate value (native binary, optional Docker). Rejected on operability, independent of the storage-efficiency question.
- **D (compute from live `wg show` only, no persistence)** cannot answer the question at all — `wg show` has no memory past the current handshake state, so "was this peer connected yesterday" is unanswerable without *some* stored history. Included in the matrix for completeness; disqualified rather than genuinely competing.

A wins by matching the actual consumption shape (one cell per peer-day) with the cheapest implementation and the tightest storage bound, at the cost of resolution nothing in scope currently needs.

## Consequences

**Positive**

- Storage growth is flat and predictable (`peers × retention_days`), independent of poll-interval tuning.
- The render query is a direct range-scan on the table's own shape — no aggregation step, no separate rollup job.
- Reuses the existing poller tick; no new scheduled job, no new adapter, no new dependency.

**Risks created**

- **Intra-day resolution is permanently lost** for this table. If a later feature needs it (e.g. per-session connect/disconnect events for an audit-grade uptime report), it will need its own storage decision — this ADR does not preclude that, but it also does not provide it. Flagged here so a future contributor doesn't assume the daily table can be "just queried harder" for something it structurally cannot answer.
- **`sample_hits` is an approximation, not exact connected-time.** It counts poll ticks with a non-null handshake, not measured session duration; a peer that connects for 10 minutes once looks similar to one that connects for 10 minutes three times, within the same day's bucket. Acceptable for a heatmap (relative intensity, not a billing metric); would need to be named explicitly if ever surfaced as a precise number.

**Accepted trade-offs**

- No sub-day resolution, ever, from this table — accepted because nothing in scope renders it.
- `sample_hits` is a tick-count proxy for connected time, not a duration measurement — accepted for the same reason.

## References

- [Issue #32](https://github.com/chriscohnen/islandr/issues/32) — the feature this ADR backs.
- `peer/ActivityPoller.java` — the existing 30 s poller this extends (currently overwrites `lastSeenAt` per peer with no history).
- [ADR-0008](0008-runtime-settings-in-db.md) — the runtime-settings-in-DB pattern the retention-window setting follows.
