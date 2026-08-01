import { defineComponent } from "vue";
import { t, formatDate, locale } from "/js/i18n.js";

// Connection activity heatmap (#32): peers x days, GitHub-contribution-graph
// style. Inverted from GitHub's layout (days as columns, not weeks) since
// peer count varies but the day axis is fixed — matching the sticky-first-
// column table pattern already used by the ACL matrix (AclMatrixView.js).
export default defineComponent({
  name: "ActivityHeatmap",
  props: {
    days: { type: Number, default: 30 },
  },
  data() {
    return {
      loading: true,
      error: null,
      result: null, // { days: [...], peers: [{ peerId, name, type, sampleHits, rxBytes, txBytes }] }
      // "hits" = connection presence (how many polls saw a handshake that day),
      // "bytes" = rxBytes+txBytes that day — same matrix, different color driver.
      metric: "hits",
    };
  },
  async mounted() {
    await this.load();
  },
  computed: {
    // Per-day max across all peers — intensity levels are relative to this,
    // per the issue spec ("4-5 intensity levels based on sample_hits share
    // of that day's max"), not an absolute scale. Same relative-to-day-max
    // approach applies to the bytes metric.
    dailyMax() {
      if (!this.result) return [];
      return this.result.days.map((_, i) =>
        Math.max(0, ...this.result.peers.map((p) => this.metricValue(p, i)))
      );
    },
    // Month header row, GitHub-contribution-graph style: one label per
    // calendar month the visible window touches, spanning (via colspan) the
    // columns that fall in that month — not one label per day/week column,
    // since here columns are days rather than weeks.
    monthSegments() {
      if (!this.result) return [];
      const fmt = new Intl.DateTimeFormat(locale.current === "de" ? "de-DE" : "en-US", { month: "short" });
      const segments = [];
      for (const iso of this.result.days) {
        const d = new Date(iso + "T00:00:00Z");
        const key = d.getUTCFullYear() * 12 + d.getUTCMonth();
        const last = segments[segments.length - 1];
        if (last && last.key === key) {
          last.span++;
        } else {
          segments.push({ key, span: 1, label: fmt.format(d) });
        }
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
        const res = await fetch(`/api/v1/peers/activity-heatmap?days=${this.days}`);
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.result = await res.json();
      } catch (e) {
        this.error = t("dashboard.heatmap_error", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    metricValue(peer, dayIndex) {
      return this.metric === "bytes"
        ? (peer.rxBytes[dayIndex] || 0) + (peer.txBytes[dayIndex] || 0)
        : peer.sampleHits[dayIndex] || 0;
    },
    level(value, dayIndex) {
      if (!value) return 0;
      const max = this.dailyMax[dayIndex];
      if (!max) return 0;
      return Math.max(1, Math.min(5, Math.ceil((value / max) * 5)));
    },
    dayLabel(iso) {
      // Short day-of-month for the column header; full date lives in the title tooltip.
      const d = new Date(iso + "T00:00:00Z");
      return String(d.getUTCDate());
    },
    formatBytes(b) {
      if (!b) return "0 B";
      const units = ["B", "KB", "MB", "GB", "TB"];
      let i = 0;
      let v = b;
      while (v >= 1024 && i < units.length - 1) {
        v /= 1024;
        i++;
      }
      return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
    },
    formatMb(b) {
      return (((b || 0) / (1024 * 1024)).toFixed(2)) + " MB";
    },
    // sample_hits is a poll-tick count, not a measured session duration (see
    // ADR-0016's own caveat on this) — ActivityPoller ticks every 30s, so
    // hits*30s is the best estimate available without a real duration column.
    formatEstimatedDuration(hits) {
      const totalMinutes = Math.round((hits * 30) / 60);
      if (totalMinutes < 60) return t("dashboard.heatmap_duration_min", { n: totalMinutes });
      const hours = Math.floor(totalMinutes / 60);
      const minutes = totalMinutes % 60;
      return t("dashboard.heatmap_duration_hm", { h: hours, m: minutes });
    },
    cellTitle(peer, dayIndex) {
      const iso = this.result.days[dayIndex];
      const hits = peer.sampleHits[dayIndex] || 0;
      let detail;
      if (this.metric === "bytes") {
        const rx = peer.rxBytes[dayIndex] || 0;
        const tx = peer.txBytes[dayIndex] || 0;
        detail = `↓ ${this.formatMb(rx)} · ↑ ${this.formatMb(tx)}`;
      } else {
        detail = hits > 0
          ? t("dashboard.heatmap_connected_approx", { duration: this.formatEstimatedDuration(hits) })
          : t("dashboard.heatmap_hits", { n: hits });
      }
      return `${peer.name} · ${formatDate(iso + "T00:00:00Z")} · ${detail}`;
    },
  },
  template: `
    <div>
      <div v-if="loading" class="muted">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="error-banner">{{ error }}</div>
      <div v-else-if="!result || result.peers.length === 0" class="muted">{{ t('dashboard.heatmap_empty') }}</div>
      <template v-else>
        <div style="margin-bottom: var(--space-2); display: inline-flex; border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden">
          <button type="button" class="btn btn-sm" :class="metric === 'hits' ? 'btn-secondary' : 'btn-ghost'"
                  style="border: none; border-radius: 0" @click="metric = 'hits'">{{ t('dashboard.heatmap_metric_hits') }}</button>
          <button type="button" class="btn btn-sm" :class="metric === 'bytes' ? 'btn-secondary' : 'btn-ghost'"
                  style="border: none; border-radius: 0" @click="metric = 'bytes'">{{ t('dashboard.heatmap_metric_bytes') }}</button>
        </div>
      <div style="overflow-x: auto">
        <table class="table" style="width: auto; min-width: 100%; border-collapse: separate; border-spacing: 2px 2px">
          <thead>
            <tr>
              <th style="position: sticky; left: 0; background: var(--surface-2); min-width: 160px"></th>
              <th v-for="seg in monthSegments" :key="seg.key" :colspan="seg.span" class="muted"
                  style="text-align: left; font-weight: 400; font-size: var(--text-xs); padding: 2px 0 2px 2px">
                {{ seg.label }}
              </th>
            </tr>
            <tr>
              <th style="position: sticky; left: 0; background: var(--surface-2); min-width: 160px; height: 24px">{{ t('dashboard.heatmap_th_peer') }}</th>
              <th v-for="(d, i) in result.days" :key="d" class="mono muted"
                  style="text-align: center; font-weight: 400; font-size: var(--text-xs); padding: 2px; min-width: 20px; height: 24px">
                {{ dayLabel(d) }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in result.peers" :key="p.peerId">
              <td style="position: sticky; left: 0; background: var(--surface); vertical-align: middle; font-size: var(--text-sm); height: 20px; padding-top: 0; padding-bottom: 0">
                {{ p.name }}
              </td>
              <td v-for="(d, i) in result.days" :key="d" :title="cellTitle(p, i)"
                  :class="'heatmap-cell heatmap-l' + level(metricValue(p, i), i)"
                  style="padding: 0"></td>
            </tr>
          </tbody>
        </table>
      </div>
      </template>
    </div>
  `,
});
