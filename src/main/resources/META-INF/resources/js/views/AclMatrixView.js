import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";
import { onEscape, onSaveShortcut } from "/js/keyboard.js";

// The Rollen × Ressourcen grant matrix (PRD §F-B, ADR-0006).
// Layout: one tab per site (the resources column-set scopes to that site),
// rows = roles, cells are tri-state:
//   ∅  no grant
//   ⓐ  all ports (current + future)
//   N  specific port count (N ≥ 1)
// Clicking a cell opens a port-picker (only when the resource has >1 port).
// Edits are local until the user clicks "Änderungen anwenden" — dirty cells
// get an amber ring. No auto-save (deliberate, per CLAUDE.md).
export default defineComponent({
  name: "AclMatrixView",
  data() {
    return {
      sites: [],
      resources: [],   // all resources across all sites, each with .ports
      roles: [],
      grants: [],      // backend snapshot: GrantCell[]
      loading: true,
      error: null,
      activeSiteId: null,
      // pending[roleId+resId] = { allPorts: bool, portIds: string[] }
      // Holds local edits since last load; deltas vs `grants` are dirty.
      pending: {},
      // Open port-picker overlay
      picker: null,    // { roleId, resourceId, portsForResource }
      saving: false,
      lang: locale.current,
      // Type grants ("all printers in Homeoffice", ACL type-grants 2026-07-28) —
      // additive, always all-ports, scoped by site like the matrix itself, but
      // not a matrix cell (no single resourceId), so it's its own small panel.
      typeGrants: [],
      newTypeGrantRoleId: "",
      newTypeGrantType: "computer",
      typeGrantSaving: false,
      typeGrantError: null,
      // Direct User → Resource grants (ADR-0024) — the same grants the Atlas
      // view's drag-and-drop creates, surfaced here for admins who'd rather
      // not use the map. Site-independent (a user can be granted a resource
      // in any site), so this list isn't scoped to activeSiteId.
      users: [],
      userGrants: [],
      newUserGrantUserId: "",
      newUserGrantResourceId: "",
      userGrantPicker: null, // { resource } — port-picker for the grant being added
      userGrantSaving: false,
      userGrantError: null,
    };
  },
  computed: {
    _lang() { return locale.current; },
    sitesById() {
      return Object.fromEntries(this.sites.map((s) => [s.id, s]));
    },
    resourcesBySite() {
      const out = {};
      for (const r of this.resources) {
        (out[r.siteId] = out[r.siteId] || []).push(r);
      }
      return out;
    },
    activeResources() {
      return this.resourcesBySite[this.activeSiteId] || [];
    },
    grantsByCell() {
      // serverGrants[roleId+"|"+resId] = GrantCell
      const m = {};
      for (const g of this.grants) {
        m[g.roleId + "|" + g.resourceId] = g;
      }
      return m;
    },
    dirty() {
      return Object.keys(this.pending).length > 0;
    },
    dirtyCount() {
      return Object.keys(this.pending).length;
    },
    dirtyBadgeLabel() {
      void this.lang;
      const n = this.dirtyCount;
      return t(n === 1 ? "acl.unsaved" : "acl.unsaved_p", { n });
    },
    typeGrantsForActiveSite() {
      return this.typeGrants.filter((g) => g.siteId === this.activeSiteId);
    },
    resourceTypeOptions() {
      void this.lang;
      return [
        ["computer", t("resources.type_computer")],
        ["nas", t("resources.type_nas")],
        ["printer", t("resources.type_printer")],
        ["router", t("resources.type_router")],
        ["camera", t("resources.type_camera")],
        ["iot", t("resources.type_iot")],
        ["virt-host", t("resources.type_virt")],
        ["rackserver", t("resources.type_rackserver")],
        ["kvm", t("resources.type_kvm")],
        ["management", t("resources.type_mgmt")],
        ["other", t("resources.type_other")],
      ];
    },
    newUserGrantResource() {
      return this.resources.find((r) => r.id === this.newUserGrantResourceId) || null;
    },
  },
  async mounted() {
    await this.load();
    this._offEscape = onEscape(() => {
      if (this.picker) this.picker = null;
      else if (this.userGrantPicker) this.userGrantPicker = null;
    });
    this._offSave = onSaveShortcut(() => { if (this.dirty) this.applyAll(); });
  },
  beforeUnmount() {
    if (this._offEscape) this._offEscape();
    if (this._offSave) this._offSave();
  },
  methods: {
    t(key, vars) { return t(key, vars); },

    async load() {
      this.loading = true;
      this.error = null;
      this.pending = {};
      try {
        const [sitesRes, resRes, rolesRes, gRes, tgRes, usersRes, ugRes] = await Promise.all([
          fetch("/api/v1/sites"),
          fetch("/api/v1/resources"),
          fetch("/api/v1/roles"),
          fetch("/api/v1/acl/matrix"),
          fetch("/api/v1/acl/type-grants"),
          fetch("/api/v1/users"),
          fetch("/api/v1/acl/user-grants"),
        ]);
        if (!sitesRes.ok || !resRes.ok || !rolesRes.ok || !gRes.ok || !tgRes.ok || !usersRes.ok || !ugRes.ok) {
          throw new Error(t("acl.err_load_matrix"));
        }
        this.sites = await sitesRes.json();
        this.resources = await resRes.json();
        this.roles = await rolesRes.json();
        this.grants = await gRes.json();
        this.typeGrants = await tgRes.json();
        this.users = await usersRes.json();
        this.userGrants = await ugRes.json();
        if (!this.activeSiteId && this.sites.length > 0) {
          this.activeSiteId = this.sites[0].id;
        }
      } catch (e) {
        this.error = t("acl.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },

    /** Effective cell state — pending edit overrides server snapshot. */
    cellState(roleId, resourceId) {
      const key = roleId + "|" + resourceId;
      const pending = this.pending[key];
      if (pending !== undefined) return pending;  // could be null = ∅
      const g = this.grantsByCell[key];
      if (!g) return null;  // ∅
      return { allPorts: g.allPorts, portIds: [...g.portIds] };
    },

    cellLabel(roleId, resource) {
      const s = this.cellState(roleId, resource.id);
      if (s === null) return "∅";
      if (s.allPorts) return "ⓐ";
      const total = resource.ports ? resource.ports.length : 0;
      return s.portIds.length + "/" + total;
    },

    isCellDirty(roleId, resourceId) {
      return this.pending[roleId + "|" + resourceId] !== undefined;
    },

    /**
     * Click a cell. Opens the port-picker so the admin explicitly chooses
     * "all ports (incl. future)" / a limited subset / none — for single-port
     * resources too. The old single-port shortcut only ever granted the one
     * specific port, so "all ports" could not be selected on a one-port resource
     * and a later-added port would silently be excluded (bug).
     *
     * Resources with zero ports defined yet still open the picker: "no access"
     * and "all ports (incl. future)" are both meaningful even before a first
     * port exists — "all ports" is exactly what you want to pre-grant a role
     * before adding ports later, so it shouldn't be blocked on port count.
     * Only "limited" needs concrete ports to choose from, and the picker
     * hides that option itself when the list is empty.
     */
    onCellClick(roleId, resource) {
      this.picker = {
        roleId,
        resourceId: resource.id,
        ports: resource.ports,
        current: this.cellState(roleId, resource.id),
      };
    },

    setPending(roleId, resourceId, next) {
      const key = roleId + "|" + resourceId;
      const server = this.grantsByCell[key];
      // Compare to server to decide whether this is still "dirty" or back to
      // the saved state (and should be removed from the pending map).
      const same = this.statesEqual(next,
          server ? { allPorts: server.allPorts, portIds: [...server.portIds] } : null);
      const pending = { ...this.pending };
      if (same) delete pending[key];
      else pending[key] = next;
      this.pending = pending;
    },

    statesEqual(a, b) {
      if (a === null && b === null) return true;
      if (a === null || b === null) return false;
      if (a.allPorts !== b.allPorts) return false;
      if (a.allPorts) return true;  // portIds irrelevant when allPorts=true
      const ap = [...a.portIds].sort();
      const bp = [...b.portIds].sort();
      if (ap.length !== bp.length) return false;
      for (let i = 0; i < ap.length; i++) if (ap[i] !== bp[i]) return false;
      return true;
    },

    pickerApply(payload) {
      // payload: { mode: 'none' | 'all' | 'limited', portIds: string[] }
      const { roleId, resourceId } = this.picker;
      let next;
      if (payload.mode === "none") next = null;
      else if (payload.mode === "all") next = { allPorts: true, portIds: [] };
      else next = { allPorts: false, portIds: payload.portIds };
      this.setPending(roleId, resourceId, next);
      this.picker = null;
    },

    discardAll() {
      if (!this.dirty) return;
      if (!confirm(t("acl.confirm_discard", { n: this.dirtyCount }))) return;
      this.pending = {};
    },

    async applyAll() {
      if (!this.dirty) return;
      this.saving = true;
      this.error = null;
      try {
        const grants = [];
        for (const key of Object.keys(this.pending)) {
          const [roleId, resourceId] = key.split("|");
          const v = this.pending[key];
          if (v === null) {
            // ∅ — server interprets allPorts=false + empty portIds as 'remove'.
            grants.push({ roleId, resourceId, allPorts: false, portIds: [] });
          } else {
            grants.push({
              roleId, resourceId,
              allPorts: v.allPorts,
              portIds: v.allPorts ? [] : v.portIds,
            });
          }
        }
        const res = await fetch("/api/v1/acl/matrix", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ grants }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        await this.load();
      } catch (e) {
        this.error = t("acl.error_apply", { error: e.message });
      } finally {
        this.saving = false;
      }
    },

    typeLabel(key) {
      const found = this.resourceTypeOptions.find(([k]) => k === key);
      return found ? found[1] : key;
    },

    async addTypeGrant() {
      if (!this.newTypeGrantRoleId || !this.activeSiteId) return;
      this.typeGrantSaving = true;
      this.typeGrantError = null;
      try {
        const res = await fetch("/api/v1/acl/type-grants", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            roleId: this.newTypeGrantRoleId,
            siteId: this.activeSiteId,
            resourceType: this.newTypeGrantType,
          }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        const created = await res.json();
        // Idempotent create can return an existing row — avoid a visual duplicate.
        if (!this.typeGrants.some((g) => g.id === created.id)) {
          this.typeGrants = [...this.typeGrants, created];
        }
        this.newTypeGrantRoleId = "";
      } catch (e) {
        this.typeGrantError = t("acl.type_grant_error", { error: e.message });
      } finally {
        this.typeGrantSaving = false;
      }
    },

    async removeTypeGrant(id) {
      this.typeGrantError = null;
      try {
        const res = await fetch("/api/v1/acl/type-grants/" + id, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.typeGrants = this.typeGrants.filter((g) => g.id !== id);
      } catch (e) {
        this.typeGrantError = t("acl.type_grant_error", { error: e.message });
      }
    },

    // Opens the same port-picker used for matrix cells, for the user+resource
    // pair currently chosen in the two dropdowns above the "add" button.
    onAddUserGrantClick() {
      if (!this.newUserGrantUserId || !this.newUserGrantResource) return;
      this.userGrantPicker = { resource: this.newUserGrantResource };
    },

    async userGrantPickerApply(payload) {
      // payload: { mode: 'none' | 'all' | 'limited', portIds: string[] }
      if (payload.mode === "none") { this.userGrantPicker = null; return; }
      await this.applyUserGrant(
          this.newUserGrantUserId, this.newUserGrantResourceId,
          payload.mode === "all", payload.mode === "all" ? [] : payload.portIds);
      this.userGrantPicker = null;
    },

    async removeUserGrant(g) {
      await this.applyUserGrant(g.userId, g.resourceId, false, []);
    },

    async applyUserGrant(userId, resourceId, allPorts, portIds) {
      this.userGrantSaving = true;
      this.userGrantError = null;
      try {
        const res = await fetch("/api/v1/acl/user-grants", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ userId, resourceId, allPorts, portIds }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        const ugRes = await fetch("/api/v1/acl/user-grants");
        if (ugRes.ok) this.userGrants = await ugRes.json();
        this.newUserGrantUserId = "";
        this.newUserGrantResourceId = "";
      } catch (e) {
        this.userGrantError = t("acl.user_grant_error", { error: e.message });
      } finally {
        this.userGrantSaving = false;
      }
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('acl.title') }}</h1>
      <div style="display: flex; gap: var(--space-3); align-items: center">
        <span v-if="dirty" class="badge" style="background: #FBBF24; color: #1F2937">
          {{ dirtyBadgeLabel }}
        </span>
        <button class="btn btn-ghost btn-sm" :disabled="!dirty" @click="discardAll">{{ t('acl.discard_btn') }}</button>
        <button class="btn btn-primary btn-sm" :disabled="!dirty || saving" @click="applyAll">
          {{ saving ? t('acl.applying') : t('acl.apply_btn') }}
        </button>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="roles.length === 0 || sites.length === 0" class="empty-state">
      <h2>{{ t('acl.empty_title') }}</h2>
      <p v-if="roles.length === 0">{{ t('acl.empty_roles') }}</p>
      <p v-else-if="sites.length === 0">{{ t('acl.empty_sites') }}</p>
    </div>

    <div v-else style="display: flex; gap: var(--space-4); align-items: flex-start; flex-wrap: wrap">
      <!-- Site list (master): a vertical, scannable list scales past a horizontal
           tab strip that overflowed off-screen once a hub had many sites. -->
      <aside style="flex: 0 0 240px; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--surface); overflow: hidden">
        <div style="padding: var(--space-3) var(--space-4); border-bottom: 1px solid var(--border); font-size: var(--text-xs); font-weight: var(--weight-medium); text-transform: uppercase; letter-spacing: 0.08em; color: var(--fg2)">
          {{ t('acl.sites_heading') }} <span class="mono" style="margin-left: 4px; letter-spacing: 0">{{ sites.length }}</span>
        </div>
        <div style="max-height: 65vh; overflow-y: auto">
          <button v-for="s in sites" :key="s.id"
                  @click="activeSiteId = s.id"
                  style="display: block; width: 100%; text-align: left; padding: var(--space-3) var(--space-4); background: transparent; border: none; border-left: 3px solid transparent; border-bottom: 1px solid var(--border); cursor: pointer"
                  :style="s.id === activeSiteId ? 'background: var(--surface-2); border-left-color: var(--accent)' : ''">
            <div style="font-weight: 600; font-size: var(--text-sm); color: var(--fg1); line-height: 1.3">{{ s.name }}</div>
            <div class="mono muted" style="font-size: var(--text-xs); margin-top: 2px">{{ s.cidr }}</div>
            <div class="muted" style="font-size: var(--text-xs); margin-top: 2px">{{ (resourcesBySite[s.id] || []).length }} {{ t('acl.resources_count') }}</div>
          </button>
        </div>
      </aside>

      <!-- Matrix (detail) for the active site; scrolls horizontally on its own. -->
      <div style="flex: 1 1 420px; min-width: 0">
        <div v-if="activeResources.length === 0" class="empty-state" style="margin: 0">
          <h2>{{ t('acl.no_res_title') }}</h2>
          <p>{{ t('acl.no_res_desc') }}</p>
        </div>

        <div v-else style="overflow-x: auto">
        <table class="table" style="width: auto; min-width: 100%">
        <thead>
          <tr>
            <th style="position: sticky; left: 0; background: var(--surface-2); min-width: 220px">{{ t('acl.th_resource') }}</th>
            <th style="position: sticky; left: 220px; background: var(--surface-2); text-align: right; padding-right: var(--space-4); white-space: nowrap; box-shadow: 1px 0 0 var(--border)">{{ t('acl.th_ports') }}</th>
            <th v-for="role in roles" :key="role.id" style="text-align: center; min-width: 120px">
              <div>{{ role.name }}</div>
              <div class="muted" style="font-size: var(--text-xs); font-weight: 400; font-family: var(--font-sans); text-transform: none; letter-spacing: 0">
                {{ role.memberCount }} {{ t(role.memberCount === 1 ? 'acl.member' : 'acl.members') }}
              </div>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in activeResources" :key="r.id">
            <td style="position: sticky; left: 0; background: var(--surface); vertical-align: middle">
              <div style="font-weight: 600; font-size: var(--text-sm); color: var(--fg1); line-height: 1.4">{{ r.name }}</div>
              <div style="font-family: var(--font-mono); font-size: var(--text-xs); color: var(--fg2); font-weight: 400; line-height: 1.3; margin-top: 2px">{{ r.ip }}</div>
            </td>
            <td style="position: sticky; left: 220px; background: var(--surface); text-align: right; padding-right: var(--space-4); vertical-align: middle; box-shadow: 1px 0 0 var(--border)">
              <span class="mono muted" style="font-size: var(--text-sm)">{{ r.ports.length }}</span>
            </td>
            <td v-for="role in roles" :key="role.id" style="text-align: center; vertical-align: middle">
              <button class="btn btn-ghost btn-sm"
                      style="min-width: 60px; font-family: var(--font-mono); font-size: var(--text-md); text-transform: none; letter-spacing: 0"
                      :style="isCellDirty(role.id, r.id) ? 'box-shadow: 0 0 0 2px #FBBF24 inset' : ''"
                      @click="onCellClick(role.id, r)">
                {{ cellLabel(role.id, r) }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
        </div>

        <div class="muted" style="margin-top: var(--space-4); font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">
          <span class="mono">∅</span> {{ t('acl.legend_none') }} &nbsp;·&nbsp;
          <span class="mono">ⓐ</span> {{ t('acl.legend_all') }} &nbsp;·&nbsp;
          <span class="mono">N</span> N {{ t('acl.legend_selected') }} &nbsp;·&nbsp;
          {{ t('acl.legend_amber') }}
        </div>

        <!-- Type grants ("all printers in Homeoffice") — additive, always
             all-ports, scoped to the active site like the matrix above but
             not a matrix cell: applies to every current AND future resource
             of that type in this site, not one concrete resourceId. Takes
             effect immediately on add/remove (unlike the matrix, no
             apply-batch step — there's no per-cell state to stage here). -->
        <div class="card card-pad" style="margin-top: var(--space-5)">
          <h2 style="margin: 0 0 var(--space-1); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('acl.type_grants_title') }}</h2>
          <div class="field-hint" style="margin-top: 0">{{ t('acl.type_grants_hint') }}</div>

          <div v-if="typeGrantError" class="error-banner" style="margin-top: var(--space-3)">{{ typeGrantError }}</div>

          <table v-if="typeGrantsForActiveSite.length > 0" class="table" style="margin-top: var(--space-3)">
            <thead>
              <tr>
                <th>{{ t('acl.th_role') }}</th>
                <th>{{ t('acl.th_type') }}</th>
                <th style="width: 40px"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="g in typeGrantsForActiveSite" :key="g.id">
                <td>{{ (roles.find(r => r.id === g.roleId) || {}).name || g.roleId }}</td>
                <td>{{ typeLabel(g.resourceType) }}</td>
                <td>
                  <button class="btn btn-ghost btn-sm" @click="removeTypeGrant(g.id)" :title="t('acl.type_grant_remove')">✕</button>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else class="muted" style="font-size: var(--text-sm)">{{ t('acl.type_grants_empty') }}</p>

          <div style="display: flex; gap: var(--space-2); align-items: center; margin-top: var(--space-3); flex-wrap: wrap">
            <select class="select" v-model="newTypeGrantRoleId" style="max-width: 220px">
              <option value="" disabled>{{ t('acl.type_grant_pick_role') }}</option>
              <option v-for="role in roles" :key="role.id" :value="role.id">{{ role.name }}</option>
            </select>
            <select class="select" v-model="newTypeGrantType" style="max-width: 200px">
              <option v-for="[key, label] in resourceTypeOptions" :key="key" :value="key">{{ label }}</option>
            </select>
            <button class="btn btn-secondary btn-sm" :disabled="!newTypeGrantRoleId || typeGrantSaving" @click="addTypeGrant">
              {{ typeGrantSaving ? t('common.loading') : t('acl.type_grant_add_btn') }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Direct User → Resource grants (ADR-0024) — the same grants the Atlas
         view's drag-and-drop creates, listed here for admins who'd rather not
         use the map. Not scoped to activeSiteId: a grant can name a resource
         in any site, so this card always shows every direct grant. -->
    <div v-if="!loading && roles.length > 0 && sites.length > 0" class="card card-pad" style="margin-top: var(--space-5)">
      <h2 style="margin: 0 0 var(--space-1); font-size: var(--text-md); font-weight: 600; color: var(--fg1)">{{ t('acl.user_grants_title') }}</h2>
      <div class="field-hint" style="margin-top: 0">{{ t('acl.user_grants_hint') }}</div>

      <div v-if="userGrantError" class="error-banner" style="margin-top: var(--space-3)">{{ userGrantError }}</div>

      <table v-if="userGrants.length > 0" class="table" style="margin-top: var(--space-3)">
        <thead>
          <tr>
            <th>{{ t('atlas.th_user') }}</th>
            <th>{{ t('acl.th_resource') }}</th>
            <th>{{ t('acl.th_ports') }}</th>
            <th style="width: 40px"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="g in userGrants" :key="g.userId + '|' + g.resourceId">
            <td>{{ g.userName }}</td>
            <td>{{ g.resourceName }} <span class="muted">— {{ g.siteName }}</span></td>
            <td class="mono">{{ g.allPorts ? t('acl.picker_all') : g.portLabels.join(', ') }}</td>
            <td>
              <button class="btn btn-ghost btn-sm" :disabled="userGrantSaving" @click="removeUserGrant(g)" :title="t('acl.type_grant_remove')">✕</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="muted" style="font-size: var(--text-sm)">{{ t('acl.user_grants_empty') }}</p>

      <div style="display: flex; gap: var(--space-2); align-items: center; margin-top: var(--space-3); flex-wrap: wrap">
        <select class="select" v-model="newUserGrantUserId" style="max-width: 220px">
          <option value="" disabled>{{ t('acl.user_grant_pick_user') }}</option>
          <option v-for="u in users" :key="u.id" :value="u.id">{{ u.name }}</option>
        </select>
        <select class="select" v-model="newUserGrantResourceId" style="max-width: 260px">
          <option value="" disabled>{{ t('acl.user_grant_pick_resource') }}</option>
          <option v-for="r in resources" :key="r.id" :value="r.id">{{ r.name }} — {{ (sitesById[r.siteId] || {}).name || r.siteId }}</option>
        </select>
        <button class="btn btn-secondary btn-sm" :disabled="!newUserGrantUserId || !newUserGrantResourceId || userGrantSaving" @click="onAddUserGrantClick">
          {{ userGrantSaving ? t('common.loading') : t('acl.user_grant_add_btn') }}
        </button>
      </div>
    </div>

    <div v-if="userGrantPicker" class="modal-backdrop" @click.self="userGrantPicker = null">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ t('acl.picker_title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="userGrantPicker = null">✕</button>
        </div>
        <PortPicker :ports="userGrantPicker.resource.ports" :current="null" @apply="userGrantPickerApply" @cancel="userGrantPicker = null" />
      </div>
    </div>

    <div v-if="picker" class="modal-backdrop" @click.self="picker = null">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ t('acl.picker_title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="picker = null">✕</button>
        </div>
        <PortPicker :ports="picker.ports" :current="picker.current" @apply="pickerApply" @cancel="picker = null" />
      </div>
    </div>
  `,
  components: {
    PortPicker: {
      props: ["ports", "current"],
      emits: ["apply", "cancel"],
      data() {
        const mode = this.current === null
            ? "none"
            : (this.current.allPorts ? "all" : "limited");
        const checked = new Set(
            this.current && !this.current.allPorts ? this.current.portIds : []);
        return { mode, checked };
      },
      computed: { _lang() { return locale.current; } },
  methods: {
        t(key, vars) { return t(key, vars); },
        toggle(portId) {
          const next = new Set(this.checked);
          if (next.has(portId)) next.delete(portId); else next.add(portId);
          this.checked = next;
        },
        confirm() {
          if (this.mode === "limited" && this.checked.size === 0) {
            // Treat "limited but nothing checked" as 'none' — the matrix
            // backend would do the same and remove the grant.
            this.$emit("apply", { mode: "none", portIds: [] });
            return;
          }
          this.$emit("apply", { mode: this.mode, portIds: Array.from(this.checked) });
        },
      },
      template: `
        <div class="modal-body">
          <div style="display: flex; flex-direction: column; gap: var(--space-3)">
            <label style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500">
              <input type="radio" value="none" v-model="mode" style="width: 16px; height: 16px; accent-color: var(--accent)" />
              {{ t('acl.picker_none') }}
            </label>
            <label style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500">
              <input type="radio" value="all" v-model="mode" style="width: 16px; height: 16px; accent-color: var(--accent)" />
              {{ t('acl.picker_all') }}
            </label>
            <label v-if="ports.length > 0" style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500">
              <input type="radio" value="limited" v-model="mode" style="width: 16px; height: 16px; accent-color: var(--accent)" />
              {{ t('acl.picker_limited') }}
            </label>
            <p v-else class="muted" style="margin: 0; font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-xs)">
              {{ t('acl.picker_no_ports_yet') }}
            </p>
            <div v-if="mode === 'limited'" style="margin-left: var(--space-6); display: flex; flex-direction: column; gap: var(--space-2)">
              <label v-for="p in ports" :key="p.id"
                     style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 400">
                <input type="checkbox" :checked="checked.has(p.id)" @change="toggle(p.id)" style="width: 16px; height: 16px; accent-color: var(--accent)" />
                <span class="mono">{{ p.port }}/{{ p.transport }}</span>
                <span>{{ p.protocol }}</span>
                <span v-if="p.label" class="muted">— {{ p.label }}</span>
              </label>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="$emit('cancel')">{{ t('acl.picker_cancel') }}</button>
          <button type="button" class="btn btn-primary" @click="confirm">{{ t('acl.picker_apply') }}</button>
        </div>
      `,
    },
  },
});
