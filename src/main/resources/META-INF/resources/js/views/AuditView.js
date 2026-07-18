import { defineComponent } from "vue";
import { t, locale, formatDate } from "/js/i18n.js";

// Reverse-chronological audit log. Backend paginates via ?before=<iso>
// — we keep a stack of cursors so "Zurück" can pop back to the previous page.
// actor / action filters round-trip to the server (fast, indexed there);
// free-text filter on the meta JSON happens client-side on the loaded page.
export default defineComponent({
  name: "AuditView",
  data() {
    return {
      rows: [],
      loading: true,
      error: null,
      // Filter inputs
      actor: "",
      action: "",
      freeText: "",
      // Cursor stack: each item is the ISO timestamp passed as ?before for that page.
      // Top of stack is the cursor of the CURRENT page; pushing happens when we go forward.
      cursorStack: [null],
      // Expanded row toggle by id — collapse meta JSON by default.
      expanded: {},
      // Purge dialog
      purgeModal: false,
      purgeBefore: "",
      purging: false,
      purgeError: null,
      lang: locale.current,
    };
  },
  computed: {
    _lang() { return locale.current; },
    visibleRows() {
      if (!this.freeText.trim()) return this.rows;
      const q = this.freeText.trim().toLowerCase();
      return this.rows.filter((r) =>
          (r.target && r.target.toLowerCase().includes(q)) ||
          (r.meta && r.meta.toLowerCase().includes(q)) ||
          (r.actor && r.actor.toLowerCase().includes(q)) ||
          (r.action && r.action.toLowerCase().includes(q))
      );
    },
    canGoBack() { return this.cursorStack.length > 1; },
    canGoNext() { return this.rows.length >= 50; },  // server default page size
    pageInfo() {
      void this.lang;
      return t("audit.page_info", { page: this.cursorStack.length, count: this.rows.length });
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
        const params = new URLSearchParams();
        const cursor = this.cursorStack[this.cursorStack.length - 1];
        if (cursor) params.set("before", cursor);
        if (this.actor.trim()) params.set("actor", this.actor.trim());
        if (this.action.trim()) params.set("action", this.action.trim());
        const url = "/api/v1/audit" + (params.toString() ? "?" + params : "");
        const res = await fetch(url);
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.rows = await res.json();
      } catch (e) {
        this.error = t("audit.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    async applyFilters() {
      // New filter set resets pagination.
      this.cursorStack = [null];
      await this.load();
    },
    async clearFilters() {
      this.actor = "";
      this.action = "";
      this.freeText = "";
      this.cursorStack = [null];
      await this.load();
    },
    async nextPage() {
      if (this.rows.length === 0) return;
      const oldest = this.rows[this.rows.length - 1].createdAt;
      this.cursorStack.push(oldest);
      await this.load();
    },
    async prevPage() {
      if (this.cursorStack.length <= 1) return;
      this.cursorStack.pop();
      await this.load();
    },
    openPurge() {
      // Default to 90 days ago as a sensible starting point.
      const d = new Date();
      d.setDate(d.getDate() - 90);
      this.purgeBefore = d.toISOString().slice(0, 10);
      this.purgeError = null;
      this.purgeModal = true;
    },
    async confirmPurge() {
      if (!this.purgeBefore) return;
      this.purging = true;
      this.purgeError = null;
      try {
        const iso = new Date(this.purgeBefore + "T00:00:00Z").toISOString();
        const res = await fetch("/api/v1/audit?before=" + encodeURIComponent(iso), { method: "DELETE" });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        const result = await res.json();
        this.purgeModal = false;
        // Reload to reflect the deletion; reset to first page.
        this.cursorStack = [null];
        await this.load();
        // Brief confirmation via error-free banner reuse — show deleted count.
        this.error = null;
        alert(t("audit.purge_success", { count: result.deleted, date: this.purgeBefore }));
      } catch (e) {
        this.purgeError = t("audit.error_purge", { error: e.message });
      } finally {
        this.purging = false;
      }
    },
    toggleExpand(id) {
      this.expanded = { ...this.expanded, [id]: !this.expanded[id] };
    },
    formatDate(iso) { return formatDate(iso); },
    prettyMeta(meta) {
      if (!meta) return "";
      try {
        return JSON.stringify(JSON.parse(meta), null, 2);
      } catch {
        return meta;
      }
    },
    // Parses target strings in two formats:
    //   old: "Resource:some-uuid"
    //   new: "Resource:Name (some-uuid)"  or  "Session:email (uuid)"
    parseTarget(target) {
      if (!target) return null;
      const colon = target.indexOf(":");
      if (colon < 0) return { prefix: null, name: target, id: null };
      const prefix = target.slice(0, colon);
      const rest = target.slice(colon + 1);
      // new format: "Name (id)" — id is inside the last parens
      const parenMatch = rest.match(/^(.+?)\s+\(([^)]+)\)$/);
      if (parenMatch) {
        return { prefix, name: parenMatch[1], id: parenMatch[2].slice(0, 8) + "…" };
      }
      // old format: bare id or other value
      const isUuid = /^[0-9a-f-]{36}$/.test(rest);
      return { prefix, name: isUuid ? null : rest, id: isUuid ? rest.slice(0, 8) + "…" : null };
    },
    actionBadgeClass(action) {
      // Coarse colour cue: delete/disable/revoke = neutral (no scary red),
      // grant/create/enable = success, login_failed = warning.
      if (action.includes("delete") || action.includes("disable") || action.includes("revoke")) return "badge-neutral";
      if (action.includes("login_failed")) return "badge-warning";
      if (action.includes("create") || action.includes("enable") || action.includes("grant") || action.includes("provision")) return "badge-success";
      return "badge-info";
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('audit.title') }}</h1>
      <button class="btn btn-ghost btn-sm" @click="openPurge">{{ t('audit.purge_btn') }}</button>
    </div>

    <div class="card card-pad" style="margin-bottom: var(--space-4)">
      <div class="form-grid" style="grid-template-columns: 1fr 1fr 1fr auto auto">
        <div class="field">
          <label for="filtActor">{{ t('audit.filter_actor') }}</label>
          <input id="filtActor" class="input mono" v-model="actor" :placeholder="t('audit.filter_actor_ph')" />
        </div>
        <div class="field">
          <label for="filtAction">{{ t('audit.filter_action') }}</label>
          <input id="filtAction" class="input mono" v-model="action" :placeholder="t('audit.filter_action_ph')" />
        </div>
        <div class="field">
          <label for="filtText">{{ t('audit.filter_text') }}</label>
          <input id="filtText" class="input" v-model="freeText" :placeholder="t('audit.filter_text_ph')" />
        </div>
        <div style="display: flex; align-items: end">
          <button class="btn btn-primary btn-sm" @click="applyFilters">{{ t('audit.btn_filter') }}</button>
        </div>
        <div style="display: flex; align-items: end">
          <button class="btn btn-ghost btn-sm" @click="clearFilters">{{ t('audit.btn_reset') }}</button>
        </div>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="visibleRows.length === 0" class="empty-state">
      <h2>{{ t('audit.empty_title') }}</h2>
      <p>{{ t('audit.empty_desc') }}</p>
    </div>

    <div v-else style="display: flex; flex-direction: column; max-height: calc(100vh - 280px); min-height: 200px">
      <div style="overflow-y: auto; flex: 1">
        <table class="table" style="width: 100%">
          <thead style="position: sticky; top: 0; z-index: 1; background: var(--surface-2)">
            <tr>
              <th>{{ t('audit.th_time') }}</th>
              <th>{{ t('audit.th_actor') }}</th>
              <th>{{ t('audit.th_action') }}</th>
              <th>{{ t('audit.th_target') }}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <template v-for="row in visibleRows" :key="row.id">
              <tr>
                <td class="mono muted" style="white-space: nowrap">{{ formatDate(row.createdAt) }}</td>
                <td>{{ row.actor }}</td>
                <td><span :class="['badge', actionBadgeClass(row.action)]" class="mono" style="font-size: var(--text-xs)">{{ row.action }}</span></td>
                <td style="font-size: var(--text-xs); white-space: nowrap">
                  <template v-if="row.target">
                    <span v-if="parseTarget(row.target).prefix"
                          class="mono muted" style="opacity: 0.45; margin-right: 3px; font-size: 10px">{{ parseTarget(row.target).prefix }}</span>
                    <span v-if="parseTarget(row.target).name"
                          style="color: var(--fg1); font-family: var(--font-sans); text-transform: none; letter-spacing: 0">{{ parseTarget(row.target).name }}</span>
                    <span v-if="parseTarget(row.target).id"
                          class="mono muted" style="margin-left: 4px">{{ parseTarget(row.target).id }}</span>
                  </template>
                  <span v-else class="muted">—</span>
                </td>
                <td style="text-align: right">
                  <button v-if="row.meta" class="btn btn-ghost btn-sm" @click="toggleExpand(row.id)">
                    {{ expanded[row.id] ? t('audit.details_hide') : t('audit.details_show') }}
                  </button>
                </td>
              </tr>
              <tr v-if="expanded[row.id] && row.meta" class="subrow">
                <td colspan="5">
                  <pre class="conf-block" style="max-height: 200px">{{ prettyMeta(row.meta) }}</pre>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
      <div style="padding-top: var(--space-3); border-top: 1px solid var(--border); display: flex; gap: var(--space-3); align-items: center; flex-shrink: 0">
        <button class="btn btn-ghost btn-sm" :disabled="!canGoBack" @click="prevPage">{{ t('audit.page_prev') }}</button>
        <button class="btn btn-ghost btn-sm" :disabled="!canGoNext" @click="nextPage">{{ t('audit.page_next') }}</button>
        <div class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">
          {{ pageInfo }}
        </div>
      </div>
    </div>

    <div v-if="purgeModal" class="modal-backdrop" @click.self="purgeModal = false">
      <div class="modal modal-sm">
        <div class="modal-header">
          <h2>{{ t('audit.purge_title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="purgeModal = false">✕</button>
        </div>
        <div class="modal-body">
          <p style="margin: 0 0 var(--space-4); color: var(--fg2); font-size: var(--text-sm)">
            {{ t('audit.purge_desc') }}
          </p>
          <div class="field" style="margin: 0">
            <label for="purgeDate">{{ t('audit.purge_label') }}</label>
            <input id="purgeDate" class="input mono" type="date" v-model="purgeBefore" />
          </div>
          <div v-if="purgeError" class="error-banner" style="margin-top: var(--space-3)">{{ purgeError }}</div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="purgeModal = false">{{ t('common.cancel') }}</button>
          <button type="button" class="btn btn-primary" :disabled="!purgeBefore || purging" @click="confirmPurge">
            {{ purging ? t('audit.purge_deleting') : t('audit.purge_btn_ok') }}
          </button>
        </div>
      </div>
    </div>
  `,
});
