import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";
import AtlasDiagram from "/js/AtlasDiagram.js";
import { Icon } from "/js/Icons.js";

export default defineComponent({
  name: "AtlasView",
  components: { AtlasDiagram, Icon },
  data() {
    return {
      graph: null,
      loading: false,
      error: null,
      tool: "grant",
      selectedRoleId: "", // "" = direct user-grant mode
      lang: locale.current,
      grantDialog: null, // { userId, resourceId, resourceName, kind, allPorts, portIds, ports }
      grantSaving: false,
    };
  },
  computed: {
    _lang() { return locale.current; },
    grantModeLabel() {
      void this.lang;
      if (!this.selectedRoleId) return t("atlas.mode_direct");
      const role = (this.graph && this.graph.roles || []).find((r) => r.id === this.selectedRoleId);
      return t("atlas.mode_role", { role: role ? role.name : this.selectedRoleId });
    },
    highlightedUserIds() {
      if (!this.selectedRoleId || !this.graph) return [];
      const ids = new Set();
      for (const e of this.graph.edges) {
        if (e.kind === "role" && e.roleId === this.selectedRoleId) ids.add(e.userId);
      }
      return Array.from(ids);
    },
    grantsForTable() {
      if (!this.graph) return [];
      const usersById = Object.fromEntries(this.graph.users.map((u) => [u.id, u]));
      const resById = Object.fromEntries(this.graph.resources.map((r) => [r.id, r]));
      return this.graph.edges.map((e) => ({
        key: e.userId + "|" + e.resourceId + "|" + e.kind + "|" + (e.roleId || ""),
        userName: (usersById[e.userId] || {}).name || e.userId,
        resourceName: (resById[e.resourceId] || {}).name || e.resourceId,
        roleName: e.kind === "user-direct" ? t("atlas.mode_direct") : e.roleName,
        portsLabel: e.allPorts ? t("acl.picker_all") : e.portLabels.join(", "),
      }));
    },
  },
  async mounted() {
    await this.load();
  },
  methods: {
    t(key, vars) { return t(key, vars); },

    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/acl/atlas");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.graph = await res.json();
      } catch (e) {
        this.error = t("atlas.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },

    async onDragGrant({ userId, resourceId }) {
      const resource = this.graph.resources.find((r) => r.id === resourceId);
      const user = this.graph.users.find((u) => u.id === userId);
      if (!resource || !user) return;
      const kind = this.selectedRoleId ? "role" : "user-direct";
      const existingFull = this.graph.edges.some((e) =>
          e.userId === userId && e.resourceId === resourceId && e.allPorts &&
          (kind === "role" ? (e.kind === "role" && e.roleId === this.selectedRoleId) : e.kind === "user-direct"));
      if (existingFull) {
        this.error = t("atlas.grant_already_full", { user: user.name, resource: resource.name });
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
        userId, resourceId,
        userName: user.name,
        resourceName: resource.name,
        kind,
        allPorts: true,
        portIds: [],
        ports,
      };
    },

    cancelGrantDialog() {
      this.grantDialog = null;
    },

    async confirmGrantDialog() {
      if (!this.grantDialog) return;
      this.grantSaving = true;
      this.error = null;
      try {
        const d = this.grantDialog;
        const url = d.kind === "role" ? "/api/v1/acl/matrix" : "/api/v1/acl/user-grants";
        const body = d.kind === "role"
            ? { grants: [{ roleId: this.selectedRoleId, resourceId: d.resourceId, allPorts: d.allPorts, portIds: d.allPorts ? [] : d.portIds }] }
            : { userId: d.userId, resourceId: d.resourceId, allPorts: d.allPorts, portIds: d.allPorts ? [] : d.portIds };
        const res = await fetch(url, {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(body),
        });
        if (!res.ok) {
          const errBody = await res.text();
          throw new Error("HTTP " + res.status + (errBody ? " — " + errBody.slice(0, 200) : ""));
        }
        this.grantDialog = null;
        await this.load();
      } catch (e) {
        this.error = t("atlas.error_grant", { error: e.message });
      } finally {
        this.grantSaving = false;
      }
    },

    async onRevokeEdge(edge) {
      const resource = this.graph.resources.find((r) => r.id === edge.resourceId);
      const user = this.graph.users.find((u) => u.id === edge.userId);
      const resourceName = resource ? resource.name : edge.resourceId;
      const userName = user ? user.name : edge.userId;
      if (edge.kind === "type-grant") {
        this.error = t("atlas.revoke_type_grant_blocked");
        return;
      }
      const roleLabel = edge.kind === "role" ? edge.roleName : t("atlas.mode_direct");
      const confirmed = confirm(t("atlas.revoke_confirm", { user: userName, role: roleLabel, resource: resourceName }));
      if (!confirmed) return;
      this.error = null;
      try {
        const url = edge.kind === "role" ? "/api/v1/acl/matrix" : "/api/v1/acl/user-grants";
        const body = edge.kind === "role"
            ? { grants: [{ roleId: edge.roleId, resourceId: edge.resourceId, allPorts: false, portIds: [] }] }
            : { userId: edge.userId, resourceId: edge.resourceId, allPorts: false, portIds: [] };
        const res = await fetch(url, {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(body),
        });
        if (!res.ok) {
          const errBody = await res.text();
          throw new Error("HTTP " + res.status + (errBody ? " — " + errBody.slice(0, 200) : ""));
        }
        await this.load();
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
      <select class="select" v-model="selectedRoleId" style="max-width: 260px">
        <option value="">{{ t('atlas.pick_role') }}</option>
        <option v-for="r in (graph ? graph.roles : [])" :key="r.id" :value="r.id">{{ r.name }}</option>
      </select>
      <span class="muted" style="font-size: var(--text-sm)">{{ grantModeLabel }}</span>

      <div style="display: flex; gap: var(--space-2); margin-left: auto">
        <button class="btn btn-sm" :class="tool === 'grant' ? 'btn-primary' : 'btn-ghost'" @click="tool = 'grant'">
          <Icon name="link" :size="14" /> {{ t('atlas.tool_grant') }}
        </button>
        <button class="btn btn-sm" :class="tool === 'revoke' ? 'btn-primary' : 'btn-ghost'" @click="tool = 'revoke'">
          <Icon name="unlink" :size="14" /> {{ t('atlas.tool_revoke') }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="graph && graph.users.length === 0" class="empty-state">
      <p>{{ t('atlas.empty_no_users') }}</p>
    </div>

    <div v-else-if="graph && graph.resources.length === 0" class="empty-state">
      <p>{{ t('atlas.empty_no_resources') }}</p>
    </div>

    <template v-else-if="graph">
      <div class="card card-pad">
        <AtlasDiagram :graph="graph" :tool="tool" :highlighted-user-ids="highlightedUserIds"
                       @drag-grant="onDragGrant" @revoke-edge="onRevokeEdge" />
      </div>

      <div class="card card-pad" style="margin-top: var(--space-4)">
        <h2 style="margin: 0 0 var(--space-3); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">
          {{ t('atlas.grants_table_title') }}
        </h2>
        <table v-if="grantsForTable.length > 0" class="table">
          <thead>
            <tr>
              <th>{{ t('atlas.th_user') }}</th>
              <th>{{ t('atlas.th_role') }}</th>
              <th>{{ t('atlas.th_resource') }}</th>
              <th>{{ t('atlas.th_ports') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in grantsForTable" :key="row.key">
              <td>{{ row.userName }}</td>
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
          <p style="margin: 0 0 var(--space-3)">
            <strong>{{ t('atlas.grant_dialog_mode') }}</strong> {{ grantModeLabel }} → {{ grantDialog.userName }} → {{ grantDialog.resourceName }}
          </p>

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
          <button type="button" class="btn btn-primary" :disabled="grantSaving" @click="confirmGrantDialog">
            {{ grantSaving ? t('common.loading') : t('atlas.grant_dialog_confirm') }}
          </button>
        </div>
      </div>
    </div>
  `,
});
