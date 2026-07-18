import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";

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
  },
  async mounted() {
    await this.load();
  },
  methods: {
    t(key, vars) { return t(key, vars); },

    async load() {
      this.loading = true;
      this.error = null;
      this.pending = {};
      try {
        const [sitesRes, resRes, rolesRes, gRes] = await Promise.all([
          fetch("/api/v1/sites"),
          fetch("/api/v1/resources"),
          fetch("/api/v1/roles"),
          fetch("/api/v1/acl/matrix"),
        ]);
        if (!sitesRes.ok || !resRes.ok || !rolesRes.ok || !gRes.ok) {
          throw new Error(t("acl.err_load_matrix"));
        }
        this.sites = await sitesRes.json();
        this.resources = await resRes.json();
        this.roles = await rolesRes.json();
        this.grants = await gRes.json();
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
     */
    onCellClick(roleId, resource) {
      if (resource.ports.length === 0) {
        // No ports defined yet — can't grant anything. No-op.
        return;
      }
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
                      :disabled="r.ports.length === 0"
                      :title="r.ports.length === 0 ? t('acl.no_ports_tip') : ''"
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
            <label style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer; text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500">
              <input type="radio" value="limited" v-model="mode" style="width: 16px; height: 16px; accent-color: var(--accent)" />
              {{ t('acl.picker_limited') }}
            </label>
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
