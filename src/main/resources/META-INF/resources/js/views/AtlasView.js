import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";
import AtlasDiagram from "/js/AtlasDiagram.js";
import { Icon } from "/js/Icons.js";
import { onEscape } from "/js/keyboard.js";

const GRANTS_PAGE_SIZE = 20;

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
      selectedUserId: null, // focused user (click-select in direct mode) — only their edges render
      selectedResourceId: null, // focused resource (click-select) — only edges reaching it render
      lang: locale.current,
      grantDialog: null, // { userId, resourceId, resourceName, kind, allPorts, portIds, ports }
      grantSaving: false,
      revokeConfirm: null, // { edge, userName, resourceName, roleLabel }
      revokeSaving: false,
      // Inclusive type filter, same semantics as the topology map's type
      // chips: empty set = show everything; a non-empty set shows only the
      // types in it (and, for edges, only what still touches a shown node).
      activeTypes: new Set(),
      // User filter is by role, not a per-user chip list — a chip per user
      // doesn't scale once a tenant has dozens of them. "" = show everyone.
      userFilterRoleId: "",
      grantsPage: 1,
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
    focusedUserName() {
      if (!this.selectedUserId || !this.graph) return "";
      const u = this.graph.users.find((u) => u.id === this.selectedUserId);
      return u ? u.name : this.selectedUserId;
    },
    focusedResourceName() {
      if (!this.selectedResourceId || !this.graph) return "";
      const r = this.graph.resources.find((r) => r.id === this.selectedResourceId);
      return r ? r.name : this.selectedResourceId;
    },
    focusLabel() {
      if (this.selectedUserId) return t("atlas.focus_user", { user: this.focusedUserName || " " });
      if (this.selectedResourceId) return t("atlas.focus_resource", { resource: this.focusedResourceName || " " });
      return "";
    },
    stats() {
      if (!this.graph) return { sites: 0, devices: 0, users: 0, grants: 0 };
      return {
        sites: new Set(this.graph.resources.map((r) => r.siteId)).size,
        devices: this.graph.resources.length,
        users: this.graph.users.length,
        grants: this.graph.edges.length,
      };
    },
    resourceTypeLabels() {
      void this.lang;
      return {
        computer: t("resources.type_computer"),
        router: t("resources.type_router"),
        printer: t("resources.type_printer"),
        nas: t("resources.type_nas"),
        camera: t("resources.type_camera"),
        iot: t("resources.type_iot"),
        "virt-host": t("resources.type_virt"),
        rackserver: t("resources.type_rackserver"),
        kvm: t("resources.type_kvm"),
        management: t("resources.type_mgmt"),
        other: t("resources.type_other"),
      };
    },
    // Every resource type actually present in the graph, with a count —
    // same shape the topology map's type-filter chips use — so the filter
    // never offers a type with nothing behind it.
    resourceTypes() {
      if (!this.graph) return [];
      const counts = new Map();
      for (const r of this.graph.resources) counts.set(r.type, (counts.get(r.type) || 0) + 1);
      return Array.from(counts.entries())
          .map(([key, count]) => ({ key, count, label: this.resourceTypeLabels[key] || key }))
          .sort((a, b) => a.label.localeCompare(b.label));
    },
    // memberUserIds comes straight from the backend's role-membership
    // resolution (same rule used for grant fan-out) — true membership, not
    // "has a grant via this role", which would miss a role with no grant yet.
    activeUserIds() {
      if (!this.userFilterRoleId || !this.graph) return [];
      const role = this.graph.roles.find((r) => r.id === this.userFilterRoleId);
      return role ? role.memberUserIds : [];
    },
    filtersActive() {
      return this.activeTypes.size > 0 || this.userFilterRoleId !== "";
    },
    // Same type/user-role filters as the diagram above, so the table below
    // it never contradicts what's currently shown on the graph.
    grantsForTable() {
      if (!this.graph) return [];
      const usersById = Object.fromEntries(this.graph.users.map((u) => [u.id, u]));
      const resById = Object.fromEntries(this.graph.resources.map((r) => [r.id, r]));
      const typeActive = this.activeTypes.size > 0;
      const userSet = this.activeUserIds.length > 0 ? new Set(this.activeUserIds) : null;
      return this.graph.edges
          .filter((e) => {
            if (typeActive) {
              const res = resById[e.resourceId];
              if (!res || !this.activeTypes.has(res.type)) return false;
            }
            if (userSet && !userSet.has(e.userId)) return false;
            return true;
          })
          .map((e) => ({
            key: e.userId + "|" + e.resourceId + "|" + e.kind + "|" + (e.roleId || ""),
            userName: (usersById[e.userId] || {}).name || e.userId,
            resourceName: (resById[e.resourceId] || {}).name || e.resourceId,
            roleName: e.kind === "user-direct" ? t("atlas.mode_direct") : e.roleName,
            portsLabel: e.allPorts ? t("acl.picker_all") : e.portLabels.join(", "),
          }));
    },
    grantsPageCount() {
      return Math.max(1, Math.ceil(this.grantsForTable.length / GRANTS_PAGE_SIZE));
    },
    grantsPageClamped() {
      return Math.min(this.grantsPage, this.grantsPageCount);
    },
    pagedGrantsForTable() {
      const start = (this.grantsPageClamped - 1) * GRANTS_PAGE_SIZE;
      return this.grantsForTable.slice(start, start + GRANTS_PAGE_SIZE);
    },
    grantsPageInfo() {
      return t("atlas.grants_page_info", { page: this.grantsPageClamped, total: this.grantsPageCount });
    },
  },
  watch: {
    // Picking a role from the dropdown shifts intent to role-mode — drop any
    // active user focus so the two selection concepts never coexist visibly.
    selectedRoleId(newVal) {
      if (newVal) { this.selectedUserId = null; this.selectedResourceId = null; }
    },
    // A changed filter can shrink the result set below the current page —
    // back to page 1 rather than landing on an empty page.
    activeTypes() { this.grantsPage = 1; },
    userFilterRoleId() { this.grantsPage = 1; },
  },
  async mounted() {
    await this.load();
    this._offEscape = onEscape(() => {
      if (this.grantDialog) this.cancelGrantDialog();
      else if (this.revokeConfirm) this.cancelRevokeConfirm();
      else if (this.selectedUserId || this.selectedResourceId) this.clearFocus();
    });
  },
  beforeUnmount() {
    if (this._offEscape) this._offEscape();
  },
  methods: {
    t(key, vars) { return t(key, vars); },

    // Clicking a user node: while a role is active, clicking someone who
    // does NOT hold that role switches the whole toolbar to direct mode and
    // focuses them (an admin trying to drag from a non-member gets redirected
    // to the mode that actually applies to that person). While already in
    // direct mode, a click simply toggles focus on that one user.
    onUserClick(userId) {
      if (this.selectedRoleId) {
        if (!this.highlightedUserIds.includes(userId)) {
          this.selectedRoleId = "";
          this.selectedResourceId = null;
          this.selectedUserId = userId;
        }
        return;
      }
      this.selectedResourceId = null;
      this.selectedUserId = this.selectedUserId === userId ? null : userId;
    },

    // Clicking a resource focuses it the same way a user click does, just
    // the mirror direction: show who/what can reach this one resource
    // instead of what this one user can reach. Mutually exclusive with role
    // filtering and user focus — a resource focus replaces both.
    onResourceClick(resourceId) {
      this.selectedRoleId = "";
      this.selectedUserId = null;
      this.selectedResourceId = this.selectedResourceId === resourceId ? null : resourceId;
    },

    clearFocus() {
      this.selectedUserId = null;
      this.selectedResourceId = null;
    },

    toggleTypeFilter(type) {
      const next = new Set(this.activeTypes);
      if (next.has(type)) next.delete(type); else next.add(type);
      this.activeTypes = next;
    },
    clearTypeFilter() {
      this.activeTypes = new Set();
    },
    clearAllFilters() {
      this.activeTypes = new Set();
      this.userFilterRoleId = "";
    },

    grantsPrevPage() {
      if (this.grantsPageClamped > 1) this.grantsPage = this.grantsPageClamped - 1;
    },
    grantsNextPage() {
      if (this.grantsPageClamped < this.grantsPageCount) this.grantsPage = this.grantsPageClamped + 1;
    },

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

    onRevokeEdge(edge) {
      const resource = this.graph.resources.find((r) => r.id === edge.resourceId);
      const user = this.graph.users.find((u) => u.id === edge.userId);
      const resourceName = resource ? resource.name : edge.resourceId;
      const userName = user ? user.name : edge.userId;
      if (edge.kind === "type-grant") {
        this.error = t("atlas.revoke_type_grant_blocked");
        return;
      }
      const roleLabel = edge.kind === "role" ? edge.roleName : t("atlas.mode_direct");
      this.revokeConfirm = { edge, userName, resourceName, roleLabel };
    },

    cancelRevokeConfirm() {
      this.revokeConfirm = null;
    },

    async confirmRevokeEdge() {
      if (!this.revokeConfirm) return;
      const edge = this.revokeConfirm.edge;
      this.revokeSaving = true;
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
        this.revokeConfirm = null;
        await this.load();
      } catch (e) {
        this.error = t("atlas.error_revoke", { error: e.message });
      } finally {
        this.revokeSaving = false;
      }
    },
  },
  template: `
    <div class="page-header" style="display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: var(--space-3)">
      <h1>{{ t('atlas.title') }}</h1>
      <div v-if="graph" style="display: flex; gap: var(--space-5)">
        <div v-for="s in [
          { label: t('atlas.stat_sites'), value: stats.sites },
          { label: t('atlas.stat_devices'), value: stats.devices },
          { label: t('atlas.stat_users'), value: stats.users },
          { label: t('atlas.stat_grants'), value: stats.grants, hint: t('atlas.stat_grants_hint') },
        ]" :key="s.label" style="text-align: center" :title="s.hint || ''">
          <div style="font-size: var(--text-xl); font-weight: 700; line-height: 1">{{ s.value }}</div>
          <div class="muted" style="font-size: var(--text-xs); text-transform: uppercase; letter-spacing: 0.05em">{{ s.label }}</div>
        </div>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <p class="muted" style="font-size: var(--text-sm); margin: 0 0 var(--space-3)">{{ t('atlas.legend_hint') }}</p>

    <!-- Type/user filter chips — same inclusive-filter pattern as the topology
         map's type row (empty selection = show everything; picking one or
         more chips narrows to just those). Unlike the topology map, Atlas
         also gets a user row, plus one combined reset once either is active. -->
    <div v-if="graph && resourceTypes.length > 0" style="display: flex; flex-wrap: wrap; gap: var(--space-2); margin-bottom: var(--space-2); font-family: var(--font-sans)">
      <button @click="clearTypeFilter"
              :class="['btn', 'btn-sm', activeTypes.size === 0 ? 'btn-secondary' : 'btn-ghost']"
              style="font-size: var(--text-xs); text-transform: none; letter-spacing: 0; height: 24px; padding: 0 10px; display: inline-flex; align-items: center; gap: 5px">
        {{ t('topology.filter_all') }}
        <span style="font-family: var(--font-mono); opacity: 0.6">{{ graph.resources.length }}</span>
      </button>
      <button v-for="ty in resourceTypes" :key="ty.key" @click="toggleTypeFilter(ty.key)"
              :class="['btn', 'btn-sm', activeTypes.has(ty.key) ? 'btn-secondary' : 'btn-ghost']"
              style="font-size: var(--text-xs); text-transform: none; letter-spacing: 0; height: 24px; padding: 0 10px; display: inline-flex; align-items: center; gap: 5px">
        <Icon :name="ty.key" :size="13" />
        {{ ty.label }}
        <span style="font-family: var(--font-mono); opacity: 0.6">{{ ty.count }}</span>
      </button>
    </div>

    <div v-if="graph && graph.users.length > 0" style="display: flex; gap: var(--space-3); align-items: center; margin-bottom: var(--space-4); flex-wrap: wrap">
      <select class="select" v-model="userFilterRoleId" style="max-width: 220px">
        <option value="">{{ t('atlas.filter_all_users') }}</option>
        <option v-for="r in graph.roles" :key="r.id" :value="r.id">{{ r.name }}</option>
      </select>
      <button v-if="filtersActive" class="btn btn-ghost btn-sm" @click="clearAllFilters">
        <Icon name="trash" :size="13" /> {{ t('atlas.filters_clear') }}
      </button>
    </div>

    <div style="display: flex; gap: var(--space-3); align-items: center; margin-bottom: var(--space-4); flex-wrap: wrap">
      <select class="select" v-model="selectedRoleId" style="max-width: 260px">
        <option value="">{{ t('atlas.pick_role') }}</option>
        <option v-for="r in (graph ? graph.roles : [])" :key="r.id" :value="r.id">{{ r.name }}</option>
      </select>
      <span class="muted" style="font-size: var(--text-sm)">{{ grantModeLabel }}</span>

      <!-- Always rendered (never v-if) and only visibility-toggled: this row
           can wrap onto two lines on narrow viewports, and an appearing/
           disappearing element here would shift the diagram below by a full
           line — including mid-interaction, right between two clicks on the
           same user node (select, then click again to deselect), landing
           the second click on whatever node the page reflow put under the
           still-stationary cursor instead of the one the admin meant. -->
      <span class="badge" :style="{ display: 'flex', alignItems: 'center', gap: '6px', visibility: (selectedUserId || selectedResourceId) ? 'visible' : 'hidden' }">
        {{ focusLabel || ' ' }}
        <button class="btn btn-ghost btn-sm" style="padding: 0 4px" @click="clearFocus" :aria-label="t('atlas.focus_clear')" :title="t('atlas.focus_clear')" :tabindex="(selectedUserId || selectedResourceId) ? 0 : -1">✕</button>
      </span>

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
        <AtlasDiagram :graph="graph" :tool="tool" :highlighted-user-ids="highlightedUserIds" :selected-user-id="selectedUserId" :selected-resource-id="selectedResourceId"
                       :active-types="Array.from(activeTypes)" :active-user-ids="Array.from(activeUserIds)"
                       @drag-grant="onDragGrant" @revoke-edge="onRevokeEdge" @user-click="onUserClick" @resource-click="onResourceClick" />
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
            <tr v-for="row in pagedGrantsForTable" :key="row.key">
              <td>{{ row.userName }}</td>
              <td>{{ row.roleName }}</td>
              <td>{{ row.resourceName }}</td>
              <td class="mono">{{ row.portsLabel }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="grantsForTable.length > 0" style="display: flex; align-items: center; gap: var(--space-3); margin-top: var(--space-3)">
          <button class="btn btn-ghost btn-sm" :disabled="grantsPageClamped <= 1" @click="grantsPrevPage">{{ t('atlas.grants_page_prev') }}</button>
          <button class="btn btn-ghost btn-sm" :disabled="grantsPageClamped >= grantsPageCount" @click="grantsNextPage">{{ t('atlas.grants_page_next') }}</button>
          <span class="muted" style="font-size: var(--text-sm)">{{ grantsPageInfo }}</span>
        </div>
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

    <div v-if="revokeConfirm" class="modal-backdrop" @click.self="cancelRevokeConfirm">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ t('atlas.revoke_dialog_title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="cancelRevokeConfirm">✕</button>
        </div>
        <div class="modal-body">
          <p style="margin: 0">
            {{ t('atlas.revoke_confirm', { user: revokeConfirm.userName, role: revokeConfirm.roleLabel, resource: revokeConfirm.resourceName }) }}
          </p>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="cancelRevokeConfirm">{{ t('atlas.grant_dialog_cancel') }}</button>
          <button type="button" class="btn btn-primary" :disabled="revokeSaving" @click="confirmRevokeEdge">
            {{ revokeSaving ? t('common.loading') : t('atlas.tool_revoke') }}
          </button>
        </div>
      </div>
    </div>
  `,
});
