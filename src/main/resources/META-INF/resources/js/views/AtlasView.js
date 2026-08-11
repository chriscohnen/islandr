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
      tool: "grant",      // "grant" | "revoke"
      lang: locale.current,
      grantDialog: null, // { peerId, resourceId, resourceName, roleId, portIds, allPorts, ports }
      grantSaving: false,
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

    async onDragGrant({ peerId, resourceId }) {
      const resource = this.graph.resources.find((r) => r.id === resourceId);
      if (!resource) return;
      const defaultRoleId = this.graph.roles.length > 0 ? this.graph.roles[0].id : "";
      // A resource already fully granted (allPorts=true) to the default role
      // needs no new grant — surface that instead of firing a no-op request.
      const existingFull = this.graph.edges.some(
          (e) => e.resourceId === resourceId && e.roleId === defaultRoleId && e.allPorts);
      if (existingFull) {
        this.error = t("atlas.grant_already_full", { resource: resource.name });
        return;
      }
      let ports = [];
      try {
        const res = await fetch("/api/v1/resources/" + resourceId);
        if (res.ok) {
          const full = await res.json();
          ports = full.ports || [];
        }
      } catch { /* dialog still opens, port list just stays empty */ }
      this.grantDialog = {
        peerId, resourceId,
        resourceName: resource.name,
        roleId: defaultRoleId,
        allPorts: true,
        portIds: [],
        ports,
      };
    },

    cancelGrantDialog() {
      this.grantDialog = null;
    },

    async confirmGrantDialog() {
      if (!this.grantDialog || !this.grantDialog.roleId) return;
      this.grantSaving = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/acl/matrix", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            grants: [{
              roleId: this.grantDialog.roleId,
              resourceId: this.grantDialog.resourceId,
              allPorts: this.grantDialog.allPorts,
              portIds: this.grantDialog.allPorts ? [] : this.grantDialog.portIds,
            }],
          }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        this.grantDialog = null;
        await this.onUserChange();
      } catch (e) {
        this.error = t("atlas.error_grant", { error: e.message });
      } finally {
        this.grantSaving = false;
      }
    },

    async onRevokeEdge(edge) {
      const resource = this.graph.resources.find((r) => r.id === edge.resourceId);
      const resourceName = resource ? resource.name : edge.resourceId;
      if (resource && resource.ownership === "type-grant") {
        this.error = t("atlas.revoke_type_grant_blocked");
        return;
      }
      const confirmed = confirm(t("atlas.revoke_confirm", { role: edge.roleName, resource: resourceName }));
      if (!confirmed) return;
      this.error = null;
      try {
        const res = await fetch("/api/v1/acl/matrix", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            grants: [{ roleId: edge.roleId, resourceId: edge.resourceId, allPorts: false, portIds: [] }],
          }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        await this.onUserChange();
      } catch (e) {
        this.error = t("atlas.error_revoke", { error: e.message });
      }
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

    <div v-if="grantDialog" class="modal-backdrop" @click.self="cancelGrantDialog">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ t('atlas.grant_dialog_title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="cancelGrantDialog">✕</button>
        </div>
        <div class="modal-body">
          <label class="label">{{ t('atlas.grant_dialog_role') }}</label>
          <select class="select" v-model="grantDialog.roleId" style="width: 100%; margin-bottom: var(--space-3)">
            <option v-for="r in graph.roles" :key="r.id" :value="r.id">{{ r.name }}</option>
          </select>

          <label style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer; margin-bottom: var(--space-2)">
            <input type="radio" :value="true" v-model="grantDialog.allPorts" style="width: 16px; height: 16px; accent-color: var(--accent)" />
            {{ t('atlas.grant_dialog_all_ports') }}
          </label>
          <label v-if="grantDialog.ports.length > 0" style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer">
            <input type="radio" :value="false" v-model="grantDialog.allPorts" style="width: 16px; height: 16px; accent-color: var(--accent)" />
            {{ t('atlas.grant_dialog_ports') }}
          </label>
          <div v-if="!grantDialog.allPorts" style="margin-left: var(--space-6); margin-top: var(--space-2); display: flex; flex-direction: column; gap: var(--space-2)">
            <label v-for="p in grantDialog.ports" :key="p.id" style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer">
              <input type="checkbox" :value="p.id" v-model="grantDialog.portIds" style="width: 16px; height: 16px; accent-color: var(--accent)" />
              <span class="mono">{{ p.port }}/{{ p.transport }}</span>
              <span>{{ p.protocol }}</span>
            </label>
          </div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="cancelGrantDialog">{{ t('atlas.grant_dialog_cancel') }}</button>
          <button type="button" class="btn btn-primary" :disabled="grantSaving || !grantDialog.roleId" @click="confirmGrantDialog">
            {{ grantSaving ? t('common.loading') : t('atlas.grant_dialog_confirm') }}
          </button>
        </div>
      </div>
    </div>
  `,
});
