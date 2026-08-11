import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";
import AtlasDiagram from "/js/AtlasDiagram.js";
import { Icon } from "/js/Icons.js";

export default defineComponent({
  name: "AtlasView",
  components: { AtlasDiagram, Icon },
  data() {
    return {
      users: [],
      selectedUserId: "",
      graph: null,       // AtlasDto.Graph | null
      loading: false,
      error: null,
      tool: "grant",      // "grant" | "revoke" — extended with real handlers in Task 6
      lang: locale.current,
    };
  },
  computed: {
    _lang() { return locale.current; },
    grantsForTable() {
      if (!this.graph) return [];
      // One row per edge, resolving peer/resource names for display.
      const peersById = Object.fromEntries(this.graph.peers.map((p) => [p.id, p]));
      const resById = Object.fromEntries(this.graph.resources.map((r) => [r.id, r]));
      return this.graph.edges.map((e) => ({
        key: e.peerId + "|" + e.resourceId + "|" + e.roleId,
        peerName: (peersById[e.peerId] || {}).name || e.peerId,
        resourceName: (resById[e.resourceId] || {}).name || e.resourceId,
        roleName: e.roleName,
        portsLabel: e.allPorts ? t("acl.picker_all") : e.portLabels.join(", "),
      }));
    },
  },
  async mounted() {
    await this.loadUsers();
  },
  methods: {
    t(key, vars) { return t(key, vars); },

    async loadUsers() {
      try {
        const res = await fetch("/api/v1/users");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.users = await res.json();
      } catch (e) {
        this.error = t("atlas.error_load", { error: e.message });
      }
    },

    async onUserChange() {
      this.graph = null;
      this.error = null;
      if (!this.selectedUserId) return;
      this.loading = true;
      try {
        const res = await fetch("/api/v1/acl/atlas/" + this.selectedUserId);
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.graph = await res.json();
      } catch (e) {
        this.error = t("atlas.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },

    onDragGrant(_payload) {
      // Wired up in Task 6 — currently a no-op so the diagram is drag-interactive
      // but doesn't yet open a dialog.
    },
    onRevokeEdge(_edge) {
      // Wired up in Task 6.
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('atlas.title') }}</h1>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div style="display: flex; gap: var(--space-3); align-items: center; margin-bottom: var(--space-4); flex-wrap: wrap">
      <select class="select" v-model="selectedUserId" @change="onUserChange" style="max-width: 280px">
        <option value="" disabled>{{ t('atlas.pick_user') }}</option>
        <option v-for="u in users" :key="u.id" :value="u.id">{{ u.name }}</option>
      </select>

      <div v-if="graph" style="display: flex; gap: var(--space-2)">
        <button class="btn btn-sm" :class="tool === 'grant' ? 'btn-primary' : 'btn-ghost'" @click="tool = 'grant'">
          <Icon name="link" :size="14" /> {{ t('atlas.tool_grant') }}
        </button>
        <button class="btn btn-sm" :class="tool === 'revoke' ? 'btn-primary' : 'btn-ghost'" @click="tool = 'revoke'">
          <Icon name="unlink" :size="14" /> {{ t('atlas.tool_revoke') }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="!selectedUserId" class="empty-state">
      <p>{{ t('atlas.empty_no_user') }}</p>
    </div>

    <div v-else-if="graph && graph.peers.length === 0" class="empty-state">
      <p>{{ t('atlas.empty_no_peers') }}</p>
    </div>

    <div v-else-if="graph && graph.resources.length === 0" class="empty-state">
      <p>{{ t('atlas.empty_no_site') }}</p>
    </div>

    <template v-else-if="graph">
      <div class="card card-pad">
        <AtlasDiagram :graph="graph" :tool="tool" @drag-grant="onDragGrant" @revoke-edge="onRevokeEdge" />
      </div>

      <div class="card card-pad" style="margin-top: var(--space-4)">
        <h2 style="margin: 0 0 var(--space-3); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">
          {{ t('atlas.grants_table_title') }}
        </h2>
        <table v-if="grantsForTable.length > 0" class="table">
          <thead>
            <tr>
              <th>{{ t('atlas.th_role') }}</th>
              <th>{{ t('atlas.th_resource') }}</th>
              <th>{{ t('atlas.th_ports') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in grantsForTable" :key="row.key">
              <td>{{ row.roleName }}</td>
              <td>{{ row.resourceName }}</td>
              <td class="mono">{{ row.portsLabel }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted" style="font-size: var(--text-sm)">{{ t('acl.type_grants_empty') }}</p>
      </div>
    </template>
  `,
});
