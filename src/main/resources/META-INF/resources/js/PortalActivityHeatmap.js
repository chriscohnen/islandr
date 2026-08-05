import { defineComponent } from "vue";
import { t, formatDate, locale } from "/js/i18n.js";

// Own-activity heatmap for the self-service portal (#43) — GitHub-
// contributions layout: rows = weekday, columns = calendar week. Unlike the
// admin's ActivityHeatmap.js (peers x days, one row per peer, days as
// columns), a single end user reads better as one compact tile than a wide
// table — and typically has just one or two devices anyway, so their
// activity is summed into a single series rather than kept per-device.
export default defineComponent({
  name: "PortalActivityHeatmap",
  props: {
    days: { type: Number, default: 90 },
    // Admin "view as" preview (#43 follow-up) — null means "my own activity".
    userId: { type: String, default: null },
  },
  data() {
    return {
      loading: true,
      error: null,
      result: null, // { days: [...], peers: [{ peerId, name, type, sampleHits, rxBytes, txBytes }] }
      metric: "hits",
    };
  },
  async mounted() {
    await this.load();
  },
  computed: {
    // One combined series across all of the user's own peers — see the
    // file-level comment for why this sums instead of keeping per-device rows.
    series() {
      if (!this.result) return [];
      return this.result.days.map((iso, i) => {
        let hits = 0, rx = 0, tx = 0;
        for (const p of this.result.peers) {
          hits += p.sampleHits[i] || 0;
          rx += p.rxBytes[i] || 0;
          tx += p.txBytes[i] || 0;
        }
        return { iso, hits, rx, tx };
      });
    },
    maxValue() {
      return Math.max(0, ...this.series.map((d) => this.metricValue(d)));
    },
    // GitHub-style grid: pad the first week on the left (days before the
    // window's own start) and the last week on the right so every column is
    // a full Mon-Sun week, then chunk into 7-row weeks.
    weeks() {
      const s = this.series;
      if (s.length === 0) return [];
      const first = new Date(s[0].iso + "T00:00:00Z");
      const leadingBlanks = (first.getUTCDay() + 6) % 7; // 0=Mon ... 6=Sun
      const cells = new Array(leadingBlanks).fill(null).concat(s);
      while (cells.length % 7 !== 0) cells.push(null);
      const out = [];
      for (let i = 0; i < cells.length; i += 7) out.push(cells.slice(i, i + 7));
      return out;
    },
    // One row per weekday, each carrying that weekday's cell across every week column.
    weekdayRows() {
      const labels = t("myaccess.activity_weekdays").split(",");
      return labels.map((label, i) => ({
        label,
        cells: this.weeks.map((w) => w[i]),
      }));
    },
    // Month label per week column (the month of that week's first real day),
    // collapsed via colspan across consecutive weeks in the same month —
    // same technique as the admin heatmap's monthSegments, just per-week
    // instead of per-day.
    monthSegments() {
      const fmt = new Intl.DateTimeFormat(locale.current === "de" ? "de-DE" : "en-US", { month: "short" });
      const segments = [];
      for (const week of this.weeks) {
        const firstReal = week.find((c) => c);
        if (!firstReal) {
          const last = segments[segments.length - 1];
          if (last) last.span++;
          else segments.push({ key: "blank", span: 1, label: "" });
          continue;
        }
        const d = new Date(firstReal.iso + "T00:00:00Z");
        const key = d.getUTCFullYear() * 12 + d.getUTCMonth();
        const last = segments[segments.length - 1];
        if (last && last.key === key) last.span++;
        else segments.push({ key, span: 1, label: fmt.format(d) });
      }
      return segments;
    },
  },
  methods: {
    t,
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const url = `/api/v1/peers/mine/activity-heatmap?days=${this.days}`
            + (this.userId ? `&userId=${encodeURIComponent(this.userId)}` : "");
        const res = await fetch(url);
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.result = await res.json();
      } catch (e) {
        this.error = t("myaccess.activity_error", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    metricValue(cell) {
      if (!cell) return 0;
      return this.metric === "bytes" ? cell.rx + cell.tx : cell.hits;
    },
    level(cell) {
      const value = this.metricValue(cell);
      if (!value || !this.maxValue) return 0;
      return Math.max(1, Math.min(5, Math.ceil((value / this.maxValue) * 5)));
    },
    formatMb(b) {
      return (((b || 0) / (1024 * 1024)).toFixed(2)) + " MB";
    },
    // sample_hits is a poll-tick count (ActivityPoller ticks every 30s), not
    // a measured session duration — same estimate/caveat as the admin heatmap.
    formatEstimatedDuration(hits) {
      const totalMinutes = Math.round((hits * 30) / 60);
      if (totalMinutes < 60) return t("myaccess.activity_duration_min", { n: totalMinutes });
      const hours = Math.floor(totalMinutes / 60);
      const minutes = totalMinutes % 60;
      return t("myaccess.activity_duration_hm", { h: hours, m: minutes });
    },
    cellTitle(cell) {
      if (!cell) return "";
      let detail;
      if (this.metric === "bytes") {
        detail = `↓ ${this.formatMb(cell.rx)} · ↑ ${this.formatMb(cell.tx)}`;
      } else {
        detail = cell.hits > 0
          ? t("myaccess.activity_connected_approx", { duration: this.formatEstimatedDuration(cell.hits) })
          : t("myaccess.activity_none");
      }
      return `${formatDate(cell.iso + "T00:00:00Z")} · ${detail}`;
    },
  },
  template: `
    <div>
      <div v-if="loading" class="muted">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="error-banner">{{ error }}</div>
      <div v-else-if="!result || result.peers.length === 0" class="muted">{{ t('myaccess.activity_empty') }}</div>
      <template v-else>
        <div style="margin-bottom: var(--space-2); display: inline-flex; border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden">
          <button type="button" class="btn btn-sm" :class="metric === 'hits' ? 'btn-secondary' : 'btn-ghost'"
                  style="border: none; border-radius: 0" @click="metric = 'hits'">{{ t('myaccess.activity_metric_hits') }}</button>
          <button type="button" class="btn btn-sm" :class="metric === 'bytes' ? 'btn-secondary' : 'btn-ghost'"
                  style="border: none; border-radius: 0" @click="metric = 'bytes'">{{ t('myaccess.activity_metric_bytes') }}</button>
        </div>
        <div style="overflow-x: auto">
          <table class="table" style="width: auto; border-collapse: separate; border-spacing: 2px 2px">
            <thead>
              <tr>
                <th style="min-width: 40px"></th>
                <th v-for="seg in monthSegments" :key="seg.key" :colspan="seg.span" class="muted"
                    style="text-align: left; font-weight: 400; font-size: var(--text-xs); padding: 2px 0 2px 2px">
                  {{ seg.label }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in weekdayRows" :key="row.label">
                <td class="muted" style="font-size: var(--text-xs); padding: 0 6px 0 0; text-align: right">{{ row.label }}</td>
                <td v-for="(cell, i) in row.cells" :key="i" :title="cellTitle(cell)"
                    :class="'heatmap-cell heatmap-l' + level(cell)"
                    style="padding: 0"></td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  `,
});
