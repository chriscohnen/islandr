import { defineComponent } from "vue";
import TopologyDiagram from "/js/TopologyDiagram.js";
import { t, locale } from "/js/i18n.js";

// Walking-skeleton dashboard. One backend round-trip (/api/v1/dashboard)
// feeds four KPI cards, a setup-status card, and two latest-activity strips.
// No background polling — admin clicks "Aktualisieren" when they want fresh
// numbers. Most cards are clickable shortcuts to the underlying view.
export default defineComponent({
  name: "DashboardView",
  components: { TopologyDiagram },
  data() {
    return {
      data: null,
      loading: true,
      error: null,
      lang: locale.current,
    };
  },
  async mounted() {
    await this.load();
  },
  computed: {
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
          severity: "info",
          text: t("dashboard.setup_retention"),
          link: "/settings",
          linkText: t("dashboard.setup_ret_action"),
        });
      }
      return issues;
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
      } catch (e) {
        this.error = t("dashboard.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    relativeTime(iso) {
      if (!iso) return "—";
      const then = new Date(iso).getTime();
      const diff = Date.now() - then;
      const s = Math.round(diff / 1000);
      if (s < 60) return "vor " + s + "s";
      const m = Math.round(s / 60);
      if (m < 60) return "vor " + m + " min";
      const h = Math.round(m / 60);
      if (h < 24) return "vor " + h + " h";
      const d = Math.round(h / 24);
      return "vor " + d + " Tagen";
    },
    formatDate(iso) {
      return iso ? new Date(iso).toLocaleString("de-DE") : "—";
    },
    actionBadgeClass(action) {
      if (!action) return "badge-info";
      if (action.includes("delete") || action.includes("disable") || action.includes("revoke")) return "badge-neutral";
      if (action.includes("login_failed")) return "badge-warning";
      if (action.includes("create") || action.includes("enable") || action.includes("grant") || action.includes("provision")) return "badge-success";
      return "badge-info";
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
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('dashboard.kpi_peers') }}</div>
          <div style="font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 600; color: var(--fg1)">{{ data.peers.total }}</div>
          <div class="muted" style="font-size: var(--text-sm); margin-top: var(--space-2); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
            <span class="badge badge-success">{{ data.peers.enabled }} {{ t('dashboard.kpi_peers_active') }}</span>
            <span v-if="data.peers.lastSeen24h > 0" class="badge badge-info" style="margin-left: var(--space-2)">{{ data.peers.lastSeen24h }} {{ t('dashboard.kpi_peers_24h') }}</span>
          </div>
        </router-link>

        <router-link to="/users" class="card card-pad" style="text-decoration: none; color: inherit; display: block">
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('dashboard.kpi_users') }}</div>
          <div style="font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 600; color: var(--fg1)">{{ data.users.total }}</div>
          <div class="muted" style="font-size: var(--text-sm); margin-top: var(--space-2); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
            <span class="badge badge-info">{{ data.users.admins }} Admin{{ data.users.admins === 1 ? "" : "s" }}</span>
          </div>
        </router-link>

        <router-link to="/roles-list" class="card card-pad" style="text-decoration: none; color: inherit; display: block">
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('dashboard.kpi_roles') }}</div>
          <div style="font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 600; color: var(--fg1)">{{ data.roles.total }}</div>
          <div class="muted" style="font-size: var(--text-sm); margin-top: var(--space-2); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
            <span :class="['badge', data.roles.withGrants > 0 ? 'badge-success' : 'badge-neutral']">
              {{ data.roles.withGrants }} {{ t('dashboard.kpi_roles_grants') }}
            </span>
          </div>
        </router-link>

        <router-link to="/networks" class="card card-pad" style="text-decoration: none; color: inherit; display: block">
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('dashboard.kpi_networks') }}</div>
          <div style="font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 600; color: var(--fg1)">{{ data.resources.sites }}</div>
          <div class="muted" style="font-size: var(--text-sm); margin-top: var(--space-2); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
            <span class="badge badge-neutral">{{ data.resources.resources }} {{ t('dashboard.kpi_resources') }}</span>
            <span class="badge badge-neutral" style="margin-left: var(--space-2)">{{ data.resources.ports }} {{ t('dashboard.kpi_ports') }}</span>
          </div>
        </router-link>

        <a href="#/firewall" class="card card-pad" @click.prevent="goToFirewall" style="text-decoration: none; color: inherit; display: block; cursor: pointer">
          <div class="section-label" style="color: var(--fg3); margin-bottom: var(--space-2)">{{ t('dashboard.kpi_firewall') }}</div>
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
      </div>

      <!-- Topology diagram: hub + sites + resources in a two-ring radial layout.
           Live peers (handshake in last 5 min) show as dots near the hub. -->
      <div class="card card-pad" style="margin-bottom: var(--space-5)">
        <div style="display: flex; justify-content: space-between; align-items: baseline; margin-bottom: var(--space-3)">
          <h2 style="font-size: var(--text-md); margin: 0">{{ t('dashboard.topology_title') }}</h2>
          <div class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">
            {{ t(data.topology.sites.length === 1 ? 'dashboard.topology_sites' : 'dashboard.topology_sites_p', { n: data.topology.sites.length }) }}
            · {{ t(data.topology.resources.length === 1 ? 'dashboard.topology_res' : 'dashboard.topology_res_p', { n: data.topology.resources.length }) }}
            <span v-if="data.topology.livePeers.length > 0">{{ t('dashboard.topology_live', { n: data.topology.livePeers.length }) }}</span>
          </div>
        </div>
        <TopologyDiagram
            :sites="data.topology.sites"
            :resources="data.topology.resources"
            :live-peers="data.topology.livePeers"
            :resource-overflow="data.topology.resourceOverflow"
            :endpoint="data.topology.hubEndpoint"
            @site="onTopologySite"
            @resource="onTopologyResource" />
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
                  <span :class="['badge', p.enabled ? 'badge-success' : 'badge-neutral']" style="font-size: var(--text-xs)">
                    {{ p.enabled ? t('peers.status_active') : t('peers.status_disabled') }}
                  </span>
                </td>
                <td class="muted" style="white-space: nowrap; font-size: var(--text-xs)" :title="formatDate(p.lastSeenAt)">
                  {{ p.lastSeenAt ? relativeTime(p.lastSeenAt) : t('dashboard.peers_never') }}
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="data.recentPeers.length > 0 && data.peers.lastSeen24h === 0" class="muted" style="margin-top: var(--space-3); font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">
            "Zuletzt gesehen" füllt sich, sobald der Activity-Poller läuft (kommt mit der nftables-Integration).
          </div>
        </div>
      </div>
    </div>
  `,
});
