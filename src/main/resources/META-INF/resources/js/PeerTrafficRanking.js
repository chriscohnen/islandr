import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";
import { Icon } from "/js/Icons.js";
import Avatar from "/js/Avatar.js";

// peer-traffic-ranking: "who filled the bandwidth cap" — a metered VPS bills
// by the month, and the heatmap (ActivityHeatmap.js) only shows a day x peer
// grid, good for patterns, useless for "which peer was it". This instead
// sums rxBytes/txBytes per peer over a whole calendar month and sorts by
// total, descending — exactly the two windows a monthly billing cycle cares
// about (this month, last month).
export default defineComponent({
  name: "PeerTrafficRanking",
  components: { Icon, Avatar },
  data() {
    return {
      loading: true,
      error: null,
      result: null, // { window, fromDay, toDay, peers: [{ peerId, peerName, userId, userName, rxBytes, txBytes, totalBytes }] }
      window_: "this-month", // "window" shadows the global DOM object — kept distinct on purpose
    };
  },
  async mounted() {
    await this.load();
  },
  methods: {
    t,
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch(`/api/v1/peers/traffic-ranking?window=${this.window_}`);
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.result = await res.json();
      } catch (e) {
        this.error = t("traffic.error", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    async selectWindow(w) {
      this.window_ = w;
      await this.load();
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
    formatRange(fromDay, toDay) {
      const fmt = new Intl.DateTimeFormat(locale.current === "de" ? "de-DE" : "en-US", { day: "numeric", month: "short" });
      return `${fmt.format(new Date(fromDay + "T00:00:00Z"))} – ${fmt.format(new Date(toDay + "T00:00:00Z"))}`;
    },
    // Share of this row's total relative to the window's single biggest
    // consumer — a bar needs *some* scale, and "relative to the top peer"
    // reads more usefully at a glance than "relative to a sum of everyone".
    barWidth(row) {
      const max = this.result?.peers?.[0]?.totalBytes || 1;
      return Math.max(2, Math.round((row.totalBytes / max) * 100)) + "%";
    },
  },
  template: `
    <div>
      <!-- Tunnel traffic only, not what the VPS actually bills — see the
           hint below the table. Repeated here so this honesty travels with
           the component wherever it's embedded, not just in a nearby card. -->
      <div style="margin-bottom: var(--space-3); display: inline-flex; border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden">
        <button type="button" class="btn btn-sm" :class="window_ === 'this-month' ? 'btn-secondary' : 'btn-ghost'"
                style="border: none; border-radius: 0" @click="selectWindow('this-month')">{{ t('traffic.window_this_month') }}</button>
        <button type="button" class="btn btn-sm" :class="window_ === 'last-month' ? 'btn-secondary' : 'btn-ghost'"
                style="border: none; border-radius: 0" @click="selectWindow('last-month')">{{ t('traffic.window_last_month') }}</button>
      </div>

      <div v-if="loading" class="muted">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="error-banner">{{ error }}</div>
      <template v-else>
        <div class="muted" style="font-size: var(--text-xs); margin-bottom: var(--space-3)">
          {{ formatRange(result.fromDay, result.toDay) }}
        </div>
        <div v-if="result.peers.length === 0" class="muted">{{ t('traffic.empty') }}</div>
        <table v-else class="table" style="width: 100%">
          <thead>
            <tr>
              <th>{{ t('traffic.th_peer') }}</th>
              <th>{{ t('traffic.th_rx') }}</th>
              <th>{{ t('traffic.th_tx') }}</th>
              <th>{{ t('traffic.th_total') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in result.peers" :key="row.peerId">
              <td>
                <span style="display: inline-flex; align-items: center; gap: 6px">
                  <Avatar v-if="row.userId" :user="{ id: row.userId, name: row.userName }" :size="16" :title="row.userName" />
                  <span>{{ row.peerName }}</span>
                  <span v-if="row.userName" class="muted" style="font-size: var(--text-xs)">· {{ row.userName }}</span>
                </span>
              </td>
              <td class="mono muted">{{ formatBytes(row.rxBytes) }}</td>
              <td class="mono muted">{{ formatBytes(row.txBytes) }}</td>
              <td class="mono" style="min-width: 160px">
                <div style="display: flex; align-items: center; gap: var(--space-2)">
                  <span>{{ formatBytes(row.totalBytes) }}</span>
                  <span style="flex: 1; height: 6px; background: var(--surface-2); border-radius: 3px; overflow: hidden; max-width: 100px">
                    <span :style="{ display: 'block', height: '100%', width: barWidth(row), background: 'var(--accent)' }"></span>
                  </span>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <p class="field-hint" style="margin-top: var(--space-3)">{{ t('traffic.hint_tunnel_only') }}</p>
        <p class="field-hint" style="margin-top: var(--space-2)">{{ t('traffic.hint_counter_reset') }}</p>
      </template>
    </div>
  `,
});
