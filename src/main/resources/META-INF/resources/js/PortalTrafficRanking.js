import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";

// Own-traffic summary for the self-service portal (peer-traffic-ranking) —
// a user who sends a lot of tunnel traffic should be able to see that for
// themselves, in the same bytes an admin's PeerTrafficRanking.js shows
// across everyone, not a separately-computed figure. Unlike the admin
// table there's no peer-owner column (every row is the caller's own
// device) and no cross-peer comparison bar — "how much did my device(s)
// use" is the whole question here, not "who used the most."
export default defineComponent({
  name: "PortalTrafficRanking",
  props: {
    // Admin "view as" preview — null means "my own traffic".
    userId: { type: String, default: null },
  },
  data() {
    return {
      loading: true,
      error: null,
      result: null, // { window, fromDay, toDay, peers: [{ peerId, peerName, rxBytes, txBytes, totalBytes }] }
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
        const url = `/api/v1/peers/mine/traffic-ranking?window=${this.window_}`
            + (this.userId ? `&userId=${encodeURIComponent(this.userId)}` : "");
        const res = await fetch(url);
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
    totalBytes() {
      return (this.result?.peers || []).reduce((sum, r) => sum + r.totalBytes, 0);
    },
  },
  template: `
    <div>
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
        <div v-if="result.peers.length === 0" class="muted">{{ t('traffic.portal_empty') }}</div>
        <template v-else>
          <div class="card card-pad" style="margin-bottom: var(--space-4); display: inline-block">
            <div class="field-hint" style="margin-bottom: var(--space-1)">{{ t('traffic.portal_total_label') }}</div>
            <div class="mono" style="font-size: var(--text-xl); font-weight: var(--weight-semibold)">{{ formatBytes(totalBytes()) }}</div>
          </div>
          <table class="table" style="width: 100%">
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
                <td>{{ row.peerName }}</td>
                <td class="mono muted">{{ formatBytes(row.rxBytes) }}</td>
                <td class="mono muted">{{ formatBytes(row.txBytes) }}</td>
                <td class="mono">{{ formatBytes(row.totalBytes) }}</td>
              </tr>
            </tbody>
          </table>
        </template>
        <p class="field-hint" style="margin-top: var(--space-3)">{{ t('traffic.hint_tunnel_only') }}</p>
        <p class="field-hint" style="margin-top: var(--space-2)">{{ t('traffic.hint_counter_reset') }}</p>
      </template>
    </div>
  `,
});
