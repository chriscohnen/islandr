import { defineComponent } from "vue";
import TopologyDiagram from "/js/TopologyDiagram.js";
import TopologyWorldMap from "/js/TopologyWorldMap.js";
import ActivityHeatmap from "/js/ActivityHeatmap.js";
import { Icon } from "/js/Icons.js";
import { t, locale, relativeTime, formatDate } from "/js/i18n.js";
import { connectionBadgeClass, connectionLabelKey } from "/js/peerStatus.js";

const LIVE_POLL_MS = 10000;
// Sustained throughput above this reads as an active transfer (a big
// download, a backup, streaming) rather than background keepalive/chatter —
// the topology diagram draws its link thicker past this point (issue #34).
const HEAVY_TRAFFIC_BPS = 50 * 1024; // 50 KB/s

// Walking-skeleton dashboard. One backend round-trip (/api/v1/dashboard)
// feeds four KPI cards, a setup-status card, and two latest-activity strips.
// No background polling — admin clicks "Aktualisieren" when they want fresh
// numbers. Most cards are clickable shortcuts to the underlying view.
export default defineComponent({
  name: "DashboardView",
  components: { TopologyDiagram, TopologyWorldMap, ActivityHeatmap, Icon },
  data() {
    return {
      data: null,
      loading: true,
      error: null,
      lang: locale.current,
      activeTab: "topology", // "topology" | "heatmap" — the heatmap grows tall with peer count, so it's a separate pane, not stacked
      liveMode: false,
      livePeers: [],       // from /api/v1/peers/live polling
      liveError: null,
      _liveTimer: null,
      _prevLiveBytes: new Map(), // publicKey -> rxBytes+txBytes from the previous poll, for the traffic-flowing delta (issue #34)
    };
  },
  async mounted() {
    await this.load();
  },
  beforeUnmount() {
    this._stopLive();
  },
  computed: {
    _lang() { return locale.current; },
    setupIssues() {
      void this.lang;
      // Inline list of "things the operator should look at". Empty = green.
      if (!this.data) return [];
      const s = this.data.setup;
      const issues = [];
      if (!s.wgConfigured) {
        issues.push({
          severity: "warning",
          text: t("dashboard.setup_wg_key"),
          link: "/settings",
          linkText: t("dashboard.setup_wg_action"),
        });
      }
      if (!s.oidcProvider) {
        issues.push({
          severity: "info",
          text: t("dashboard.setup_oidc"),
          link: "/identity",
          linkText: t("dashboard.setup_oidc_action"),
        });
      }
      if (s.privateKeyRetention === "plaintext") {
        issues.push({
          severity: "warning",
          text: t("dashboard.setup_retention"),
          link: "/settings",
          linkText: t("dashboard.setup_ret_action"),
        });
      }
      if (s.firewallDryRun) {
        issues.push({
          severity: "warning",
          text: t("dashboard.firewall_dry_run"),
          link: "/settings",
          linkText: t("dashboard.firewall_dry_run_action"),
        });
      }
      return issues;
    },
    // World-map tab only makes sense once there's actually something
    // geographic to show — ADR-0021 (revised 2026-07-26): hub coordinates
    // set AND at least one geocoded site-peer, so a hub + single remote
    // site (a real, common case) already gets a map instead of needing a
    // second site purely to clear an arbitrary count.
    worldMapAvailable() {
      if (!this.data) return false;
      const hasHub = this.data.topology.hubLat != null && this.data.topology.hubLon != null;
      const geocodedSites = this.data.topology.sites.filter((s) => s.gatewayLat != null && s.gatewayLng != null).length;
      return hasHub && geocodedSites >= 1;
    },
  },
  methods: {
    t(key, vars) { return t(key, vars); },

    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/dashboard");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.data = await res.json();
        if (this.activeTab === "map" && !this.worldMapAvailable) this.activeTab = "topology";
      } catch (e) {
        this.error = t("dashboard.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    relativeTime(iso) { return relativeTime(iso); },
    formatDate(iso) { return formatDate(iso); },
    connectionBadgeClass(p) { return connectionBadgeClass(p); },
    connectionLabelKey(p) { return connectionLabelKey(p); },
    actionBadgeClass(action) {
      if (!action) return "badge-info";
      if (action.includes("delete") || action.includes("disable") || action.includes("revoke")) return "badge-neutral";
      if (action.includes("login_failed")) return "badge-warning";
      if (action.includes("create") || action.includes("enable") || action.includes("grant") || action.includes("provision")) return "badge-success";
      return "badge-info";
    },
    async toggleLive() {
      if (this.liveMode) {
        this._stopLive();
      } else {
        this.liveMode = true;
        await this._pollLive();
        this._liveTimer = setInterval(() => this._pollLive(), LIVE_POLL_MS);
      }
    },
    _stopLive() {
      this.liveMode = false;
      if (this._liveTimer) { clearInterval(this._liveTimer); this._liveTimer = null; }
      this.livePeers = [];
      this.liveError = null;
      this._prevLiveBytes.clear();
    },
    async _pollLive() {
      try {
        const res = await fetch("/api/v1/peers/live");
        if (!res.ok) throw new Error("HTTP " + res.status);
        const peers = await res.json();
        // Issue #34: the endpoint only gives cumulative rx/tx counters, so
        // "traffic is currently flowing" — and how much — has to be derived
        // client-side from the delta against the previous poll, no new
        // storage needed. Three tiers, not a continuous scale: "idle" (no
        // movement), "flowing" (some bytes moved), "flowing-heavy" (moving
        // fast enough that it reads as an active transfer, not background
        // chatter) — the diagram reinforces this with line thickness.
        for (const p of peers) {
          const total = (p.rxBytes || 0) + (p.txBytes || 0);
          const prevTotal = this._prevLiveBytes.get(p.publicKey);
          const delta = prevTotal != null ? total - prevTotal : 0;
          const bytesPerSec = delta / (LIVE_POLL_MS / 1000);
          p.trafficTier = bytesPerSec >= HEAVY_TRAFFIC_BPS ? "flowing-heavy"
                         : bytesPerSec > 0                  ? "flowing"
                         : "idle";
          this._prevLiveBytes.set(p.publicKey, total);
        }
        this.livePeers = peers;
        this.liveError = null;
      } catch (e) {
        this.liveError = e.message;
      }
    },
    formatBytes(b) {
      if (b == null) return "—";
      if (b < 1024) return b + " B";
      if (b < 1024 * 1024) return (b / 1024).toFixed(1) + " KB";
      if (b < 1024 * 1024 * 1024) return (b / (1024 * 1024)).toFixed(1) + " MB";
      return (b / (1024 * 1024 * 1024)).toFixed(1) + " GB";
    },
    providerLabel(key) {
      if (key === "microsoft") return "Microsoft 365";
      if (key === "google") return "Google";
      return "—";
    },
    goToPeers() {
      this.$router.push({ name: "peers" });
    },
    onTopologySite(siteId) {
      this.$router.push({ name: "resources", params: { siteId } });
    },
    onTopologyResource({ siteId }) {
      // No per-resource detail view yet — same destination as site click;
      // the resource list is where the user gets the rest of the picture.
      this.$router.push({ name: "resources", params: { siteId } });
    },
    goToFirewall() {
      this.$router.push({ name: "firewall" });
    },
    firewallStatusLabel(status) {
      if (status === "ok") return t("dashboard.fw_ok");
      if (status === "failed") return t("dashboard.fw_failed");
      return t("dashboard.fw_never");
    },
    firewallStatusBadge(status) {
      if (status === "ok") return "badge-success";
      if (status === "failed") return "badge-warning";
      return "badge-neutral";
    },
    // Hub health (#73) — CPU/memory/swap sampled server-side from /proc.
    // "unavailable" (no /proc, e.g. a non-Linux dev machine) degrades to a
    // muted "—", never an error state — this is informational, not a
    // feature the app depends on.
    hostHealthBadge(status) {
      if (status === "critical") return "badge-danger";
      if (status === "high") return "badge-warning";
      if (status === "ok") return "badge-success";
      return "badge-neutral";
    },
    hostHealthStatusLabel(status) {
      if (status === "critical") return t("dashboard.hub_critical");
      if (status === "high") return t("dashboard.hub_high");
      if (status === "ok") return t("dashboard.hub_ok");
      return t("dashboard.hub_unavailable");
    },
    hostHealthCpuLabel(h) {
      return h.cpuPercent != null ? Math.round(h.cpuPercent) + "%" : "—";
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('dashboard.title') }}</h1>
      <button class="btn btn-ghost btn-sm" :disabled="loading" @click="load">
        {{ loading ? t('dashboard.loading') : t('dashboard.refresh_btn') }}
      </button>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div v-if="loading && !data" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="data">
      <!-- Setup status — only shown when there's something to say. Empty means good. -->
      <div v-if="setupIssues.length > 0" style="display: grid; gap: var(--space-3); margin-bottom: var(--space-5)">
        <div v-for="(issue, i) in setupIssues" :key="i"
             :class="['callout', issue.severity === 'warning' ? 'callout-warning' : 'callout-info']">
          <div>
            {{ issue.text }}
            <router-link v-if="issue.link" :to="issue.link" style="margin-left: var(--space-2)">{{ issue.linkText }} →</router-link>
          </div>
        </div>
      </div>
      <div v-else class="callout callout-info" style="margin-bottom: var(--space-5)">
        <div>{{ t('dashboard.setup_complete') }}</div>
      </div>

      <!-- KPI grid -->
      <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--space-4); margin-bottom: var(--space-6)">
        <router-link to="/peers" class="card card-pad" style="text-decoration: none; color: inherit; display: block">
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2); display: flex; align-items: center; gap: var(--space-2)">
            <Icon name="peers" :size="13" style="opacity: 0.6" />{{ t('dashboard.kpi_peers') }}
          </div>
          <div style="font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 600; color: var(--fg1)">{{ data.peers.total }}</div>
          <div class="muted" style="font-size: var(--text-sm); margin-top: var(--space-2); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
            <span class="badge badge-success">{{ data.peers.enabled }} {{ t('dashboard.kpi_peers_active') }}</span>
            <span v-if="data.peers.lastSeen24h > 0" class="badge badge-info" style="margin-left: var(--space-2)">{{ data.peers.lastSeen24h }} {{ t('dashboard.kpi_peers_24h') }}</span>
          </div>
        </router-link>

        <router-link to="/users" class="card card-pad" style="text-decoration: none; color: inherit; display: block">
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2); display: flex; align-items: center; gap: var(--space-2)">
            <Icon name="users" :size="13" style="opacity: 0.6" />{{ t('dashboard.kpi_users') }}
          </div>
          <div style="font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 600; color: var(--fg1)">{{ data.users.total }}</div>
          <div class="muted" style="font-size: var(--text-sm); margin-top: var(--space-2); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
            <span class="badge badge-info">{{ data.users.admins }} Admin{{ data.users.admins === 1 ? "" : "s" }}</span>
          </div>
        </router-link>

        <router-link to="/roles-list" class="card card-pad" style="text-decoration: none; color: inherit; display: block">
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2); display: flex; align-items: center; gap: var(--space-2)">
            <Icon name="roles" :size="13" style="opacity: 0.6" />{{ t('dashboard.kpi_roles') }}
          </div>
          <div style="font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 600; color: var(--fg1)">{{ data.roles.total }}</div>
          <div class="muted" style="font-size: var(--text-sm); margin-top: var(--space-2); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
            <span :class="['badge', data.roles.withGrants > 0 ? 'badge-success' : 'badge-neutral']">
              {{ data.roles.withGrants }} {{ t('dashboard.kpi_roles_grants') }}
            </span>
          </div>
        </router-link>

        <router-link to="/networks" class="card card-pad" style="text-decoration: none; color: inherit; display: block">
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2); display: flex; align-items: center; gap: var(--space-2)">
            <Icon name="networks" :size="13" style="opacity: 0.6" />{{ t('dashboard.kpi_networks') }}
          </div>
          <div style="font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 600; color: var(--fg1)">{{ data.resources.sites }}</div>
          <div class="muted" style="font-size: var(--text-sm); margin-top: var(--space-2); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
            <span class="badge badge-neutral">{{ data.resources.resources }} {{ t('dashboard.kpi_resources') }}</span>
            <span class="badge badge-neutral" style="margin-left: var(--space-2)">{{ data.resources.ports }} {{ t('dashboard.kpi_ports') }}</span>
          </div>
        </router-link>

        <a href="#/firewall" class="card card-pad" @click.prevent="goToFirewall" style="text-decoration: none; color: inherit; display: block; cursor: pointer">
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2); display: flex; align-items: center; gap: var(--space-2)">
            <Icon name="firewall" :size="13" style="opacity: 0.6" />{{ t('dashboard.kpi_firewall') }}
          </div>
          <div style="font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 600; color: var(--fg1)">{{ data.firewall.ruleCount }}</div>
          <div class="muted" style="font-size: var(--text-sm); margin-top: var(--space-2); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
            <span :class="['badge', firewallStatusBadge(data.firewall.status)]">
              {{ firewallStatusLabel(data.firewall.status) }}
            </span>
            <span v-if="data.firewall.lastOkAt" class="muted" style="margin-left: var(--space-2); font-size: var(--text-xs)">
              {{ relativeTime(data.firewall.lastOkAt) }}
            </span>
          </div>
          <div v-if="data.firewall.stderr" class="muted" style="margin-top: var(--space-2); font-size: var(--text-xs); color: var(--warning-fg, #b45309); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-family: var(--font-mono)">
            {{ data.firewall.stderr }}
          </div>
        </a>

        <div class="card card-pad">
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2); display: flex; align-items: center; gap: var(--space-2)">
            <Icon name="server" :size="13" style="opacity: 0.6" />{{ t('dashboard.kpi_hub') }}
          </div>
          <div style="font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 600; color: var(--fg1)">{{ hostHealthCpuLabel(data.hostHealth) }}</div>
          <div class="muted" style="font-size: var(--text-sm); margin-top: var(--space-2); font-family: var(--font-sans); text-transform: none; letter-spacing: 0; display: flex; flex-wrap: wrap; gap: var(--space-2); align-items: center">
            <span :class="['badge', hostHealthBadge(data.hostHealth.status)]">{{ hostHealthStatusLabel(data.hostHealth.status) }}</span>
            <span v-if="data.hostHealth.status !== 'unavailable'" class="badge badge-neutral">{{ t('dashboard.hub_mem') }} {{ formatBytes(data.hostHealth.memUsedBytes) }} / {{ formatBytes(data.hostHealth.memTotalBytes) }}</span>
            <span v-if="data.hostHealth.swapTotalBytes > 0" class="badge badge-neutral">{{ t('dashboard.hub_swap') }} {{ formatBytes(data.hostHealth.swapUsedBytes) }} / {{ formatBytes(data.hostHealth.swapTotalBytes) }}</span>
          </div>
        </div>
      </div>

      <!-- Network visualization: Topology (hub + sites + resources) and the
           connection activity heatmap share one card, switched via tabs —
           the heatmap grows tall as the peer count grows, so it can't just
           stack under the topology diagram the way it used to. -->
      <div class="card card-pad" style="margin-bottom: var(--space-5)">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-3); flex-wrap: wrap; gap: var(--space-3)">
          <div style="display: flex; gap: var(--space-2)">
            <button class="btn btn-sm" :class="activeTab === 'topology' ? 'btn-secondary' : 'btn-ghost'" @click="activeTab = 'topology'">
              {{ t('dashboard.topology_title') }}
            </button>
            <button v-if="worldMapAvailable" class="btn btn-sm" :class="activeTab === 'map' ? 'btn-secondary' : 'btn-ghost'" @click="activeTab = 'map'">
              {{ t('dashboard.worldmap_title') }}
            </button>
            <button class="btn btn-sm" :class="activeTab === 'heatmap' ? 'btn-secondary' : 'btn-ghost'" @click="activeTab = 'heatmap'">
              {{ t('dashboard.heatmap_title') }}
            </button>
          </div>

          <div v-if="activeTab === 'topology'" style="display: flex; align-items: center; gap: var(--space-3)">
            <div class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">
              {{ t(data.topology.sites.length === 1 ? 'dashboard.topology_sites' : 'dashboard.topology_sites_p', { n: data.topology.sites.length }) }}
              · {{ t(data.topology.resources.length === 1 ? 'dashboard.topology_res' : 'dashboard.topology_res_p', { n: data.topology.resources.length }) }}
              <span v-if="!liveMode && data.topology.livePeers.length > 0">{{ t('dashboard.topology_live', { n: data.topology.livePeers.length }) }}</span>
              <span v-if="liveMode && livePeers.length > 0" style="color: var(--status-ok)">● {{ livePeers.length }} {{ t('dashboard.live_connected') }}</span>
            </div>
            <button class="btn btn-sm" :class="liveMode ? 'btn-primary' : 'btn-ghost'" @click="toggleLive"
                    style="display: inline-flex; align-items: center; gap: 4px">
              <span v-if="liveMode" style="display:inline-block;width:8px;height:8px;border-radius:50%;background:currentColor;animation:pulse 1s ease-in-out infinite"></span>
              {{ liveMode ? t('dashboard.live_btn_stop') : t('dashboard.live_btn_start') }}
            </button>
          </div>
        </div>

        <div v-show="activeTab === 'topology'">
          <TopologyDiagram
              :sites="data.topology.sites"
              :resources="data.topology.resources"
              :live-peers="liveMode ? livePeers : data.topology.livePeers"
              :resource-overflow="data.topology.resourceOverflow"
              :endpoint="data.topology.hubEndpoint"
              :hub-label="data.topology.hubLabel"
              @site="onTopologySite"
              @resource="onTopologyResource" />

          <!-- Live peer list — only shown when liveMode is active -->
          <div v-if="liveMode" style="margin-top: var(--space-4); border-top: 1px solid var(--border); padding-top: var(--space-3)">
            <div v-if="liveError" class="muted" style="font-size: var(--text-sm); color: var(--status-warn)">{{ liveError }}</div>
            <div v-else-if="livePeers.length === 0" class="muted" style="font-size: var(--text-sm)">{{ t('dashboard.live_empty') }}</div>
            <table v-else class="table" style="font-size: var(--text-sm)">
              <thead>
                <tr>
                  <th>{{ t('dashboard.live_th_name') }}</th>
                  <th>{{ t('dashboard.live_th_ip') }}</th>
                  <th>{{ t('dashboard.live_th_endpoint') }}</th>
                  <th>{{ t('dashboard.live_th_handshake') }}</th>
                  <th>↓ RX</th>
                  <th>↑ TX</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="p in livePeers" :key="p.publicKey">
                  <td>
                    <span v-if="p.name">{{ p.name }}</span>
                    <span v-else class="muted mono" style="font-size:11px">{{ p.publicKey.slice(0,16) }}…</span>
                  </td>
                  <td class="mono">{{ p.assignedIp || '—' }}</td>
                  <td class="mono" style="font-size:11px">{{ p.endpoint || '—' }}</td>
                  <td class="muted">{{ relativeTime(p.lastHandshake) }}</td>
                  <td class="mono muted">{{ formatBytes(p.rxBytes) }}</td>
                  <td class="mono muted">{{ formatBytes(p.txBytes) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- World-map topology view (ADR-0021, #11): geographic alternative to
             the radial diagram above, for operators with distributed sites. -->
        <div v-if="worldMapAvailable" v-show="activeTab === 'map'">
          <TopologyWorldMap
              :sites="data.topology.sites"
              :live-peers="liveMode ? livePeers : data.topology.livePeers"
              :hub-lat="data.topology.hubLat"
              :hub-lon="data.topology.hubLon"
              :hub-label="data.topology.hubLabel"
              @site="onTopologySite" />
        </div>

        <!-- Connection activity heatmap: peers x days, who was connected when (#32) -->
        <div v-show="activeTab === 'heatmap'">
          <ActivityHeatmap :days="30" />
        </div>
      </div>

      <!-- Two side-by-side activity strips -->
      <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(420px, 1fr)); gap: var(--space-5)">
        <div class="card card-pad">
          <div style="display: flex; justify-content: space-between; align-items: baseline; margin-bottom: var(--space-3)">
            <h2 style="font-size: var(--text-md); margin: 0">{{ t('dashboard.activity_title') }}</h2>
            <router-link to="/audit" class="btn btn-ghost btn-sm">{{ t('dashboard.activity_link') }}</router-link>
          </div>
          <div v-if="data.recentAudit.length === 0" class="muted">{{ t('dashboard.activity_empty') }}</div>
          <table v-else class="table" style="width: 100%">
            <tbody>
              <tr v-for="row in data.recentAudit" :key="row.id">
                <td class="muted" style="white-space: nowrap; font-size: var(--text-xs)" :title="formatDate(row.createdAt)">
                  {{ relativeTime(row.createdAt) }}
                </td>
                <td style="font-size: var(--text-sm)">{{ row.actor }}</td>
                <td>
                  <span :class="['badge', actionBadgeClass(row.action)]" class="mono" style="font-size: var(--text-xs)">{{ row.action }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="card card-pad">
          <div style="display: flex; justify-content: space-between; align-items: baseline; margin-bottom: var(--space-3)">
            <h2 style="font-size: var(--text-md); margin: 0">{{ t('dashboard.peers_title') }}</h2>
            <router-link to="/peers" class="btn btn-ghost btn-sm">{{ t('dashboard.peers_link') }}</router-link>
          </div>
          <div v-if="data.recentPeers.length === 0" class="muted">{{ t('dashboard.peers_empty') }}</div>
          <table v-else class="table" style="width: 100%">
            <tbody>
              <tr v-for="p in data.recentPeers" :key="p.id">
                <td>{{ p.name }}</td>
                <td class="mono muted" style="font-size: var(--text-xs)">{{ p.assignedIp }}</td>
                <td style="font-size: var(--text-sm)">{{ p.userName }}</td>
                <td>
                  <span v-if="!p.enabled" class="badge badge-neutral" style="font-size: var(--text-xs)">
                    <span class="dot"></span>{{ t('peers.status_disabled') }}
                  </span>
                  <span v-else :class="['badge', connectionBadgeClass(p)]" style="font-size: var(--text-xs)">
                    <span class="dot"></span>{{ t(connectionLabelKey(p)) }}
                  </span>
                </td>
                <td class="muted" style="white-space: nowrap; font-size: var(--text-xs)" :title="formatDate(p.lastSeenAt)">
                  {{ p.lastSeenAt ? relativeTime(p.lastSeenAt) : t('dashboard.peers_never') }}
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="data.recentPeers.length > 0 && data.peers.lastSeen24h === 0" class="muted" style="margin-top: var(--space-3); font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">
            {{ t('dashboard.last_seen_hint') }}
          </div>
        </div>
      </div>
    </div>
  `,
});
