import { defineComponent } from "vue";
import { t, formatDate } from "/js/i18n.js";

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
      result: null, // { days: [...], peers: [{ peerId, name, type, sampleHits }] }
    };
  },
  async mounted() {
    await this.load();
  },
  computed: {
    // Per-day max across all peers — intensity levels are relative to this,
    // per the issue spec ("4-5 intensity levels based on sample_hits share
    // of that day's max"), not an absolute scale.
    dailyMax() {
      if (!this.result) return [];
      return this.result.days.map((_, i) =>
        Math.max(0, ...this.result.peers.map((p) => p.sampleHits[i] || 0))
      );
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
    level(hits, dayIndex) {
      if (!hits) return 0;
      const max = this.dailyMax[dayIndex];
      if (!max) return 0;
      return Math.max(1, Math.min(5, Math.ceil((hits / max) * 5)));
    },
    dayLabel(iso) {
      // Short day-of-month for the column header; full date lives in the title tooltip.
      const d = new Date(iso + "T00:00:00Z");
      return String(d.getUTCDate());
    },
    cellTitle(peerName, iso, hits) {
      return `${peerName} · ${formatDate(iso + "T00:00:00Z")} · ${t("dashboard.heatmap_hits", { n: hits })}`;
    },
  },
  template: `
    <div>
      <div v-if="loading" class="muted">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="error-banner">{{ error }}</div>
      <div v-else-if="!result || result.peers.length === 0" class="muted">{{ t('dashboard.heatmap_empty') }}</div>
      <div v-else style="overflow-x: auto">
        <table class="table" style="width: auto; min-width: 100%; border-collapse: separate; border-spacing: 2px 2px">
          <thead>
            <tr>
              <th style="position: sticky; left: 0; background: var(--surface-2); min-width: 160px">{{ t('dashboard.heatmap_th_peer') }}</th>
              <th v-for="(d, i) in result.days" :key="d" class="mono muted"
                  style="text-align: center; font-weight: 400; font-size: var(--text-xs); padding: 2px; min-width: 18px">
                {{ dayLabel(d) }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in result.peers" :key="p.peerId">
              <td style="position: sticky; left: 0; background: var(--surface); vertical-align: middle; font-size: var(--text-sm)">
                {{ p.name }}
              </td>
              <td v-for="(d, i) in result.days" :key="d" :title="cellTitle(p.name, d, p.sampleHits[i])"
                  :class="'heatmap-cell heatmap-l' + level(p.sampleHits[i], i)"
                  style="padding: 0"></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `,
});
