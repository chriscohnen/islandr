import { defineComponent } from "vue";
import { peerModalMixin, peerModalTemplate } from "/js/peerModal.js";
import Avatar from "/js/Avatar.js";
import { Icon } from "/js/Icons.js";
import { t, locale, formatDate } from "/js/i18n.js";
import { onEscape, onSlashFocus } from "/js/keyboard.js";

// User management. Each row gets a "+ Peer" button that opens the shared
// peer-create modal. The full peer list across all users lives in PeersView.
export default defineComponent({
  name: "UsersView",
  props: {
    retention: { type: String, default: "never" },
    googleWsAvailable: { type: Boolean, default: false },
  },
  mixins: [peerModalMixin],
  components: { Avatar, Icon },
  data() {
    return {
      users: [],
      loading: true,
      error: null,
      quickFilter: "", // matches against display name, nickname, real name, or email — substring, case-insensitive
      newUser: { name: "", email: "" },
      submitting: false,
      lang: locale.current,
      editingNicknameId: null,
      nicknameInput: "",
      editingEmailId: null,
      emailInput: "",
      editingPasswordId: null,
      passwordInput: "",
      // Google Workspace import dialog
      gwsOpen: false,
      gwsLoading: false,
      gwsError: null,
      gwsUsers: [],
      gwsConfigured: false,
      gwsSelected: new Set(),
      gwsImporting: false,
      gwsResult: null,
    };
  },
  computed: {
    _lang() { return locale.current; },
    modalUserName() {
      const u = this.users.find((x) => x.id === this.modalUserId);
      return u ? `${u.displayName} (${u.email})` : null;
    },
    filteredUsers() {
      const q = this.quickFilter.trim().toLowerCase();
      if (!q) return this.users;
      return this.users.filter((u) =>
        (u.displayName || "").toLowerCase().includes(q)
        || (u.nickname || "").toLowerCase().includes(q)
        || (u.name || "").toLowerCase().includes(q)
        || (u.email || "").toLowerCase().includes(q));
    },
  },
  async mounted() {
    await this.load();
    this._offEscape = onEscape(() => { if (this.gwsOpen) this.closeGwsDialog(); });
    this._offSlash = onSlashFocus(() => this.$refs.searchInput);
  },
  beforeUnmount() {
    if (this._offEscape) this._offEscape();
    if (this._offSlash) this._offSlash();
  },
  methods: {
    t(key, vars) { return t(key, vars); },

    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/users");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.users = await res.json();
      } catch (e) {
        this.error = t("users.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },

    async createUser() {
      if (!this.newUser.name || !this.newUser.email) return;
      this.submitting = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/users", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(this.newUser),
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.newUser = { name: "", email: "" };
        await this.load();
      } catch (e) {
        this.error = t("users.error_create", { error: e.message });
      } finally {
        this.submitting = false;
      }
    },

    async deleteUser(id) {
      if (!confirm(t("users.confirm_delete"))) return;
      try {
        const res = await fetch("/api/v1/users/" + id, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        this.error = t("users.error_delete", { error: e.message });
      }
    },

    async toggleEnabled(user) {
      try {
        const res = await fetch("/api/v1/users/" + user.id + "/enabled", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ enabled: !user.enabled }),
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        this.error = t("users.error_toggle", { error: e.message });
      }
    },

    async toggleAdmin(user) {
      const next = !user.isAdmin;
      const verb = next
        ? t("users.confirm_admin_grant", { name: user.displayName })
        : t("users.confirm_admin_revoke", { name: user.displayName });
      if (!confirm(verb)) return;
      try {
        const res = await fetch("/api/v1/users/" + user.id + "/admin", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ isAdmin: next }),
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        this.error = t("users.error_role", { error: e.message });
      }
    },

    startNicknameEdit(u) {
      this.editingNicknameId = u.id;
      this.nicknameInput = u.nickname || "";
    },
    cancelNicknameEdit() {
      this.editingNicknameId = null;
      this.nicknameInput = "";
    },
    async saveNickname(userId) {
      try {
        const res = await fetch("/api/v1/users/" + userId + "/nickname", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ nickname: this.nicknameInput }),
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
        this.cancelNicknameEdit();
      } catch (e) {
        this.error = t("users.error_nickname", { error: e.message });
      }
    },

    startEmailEdit(u) {
      this.editingEmailId = u.id;
      this.emailInput = u.email || "";
    },
    cancelEmailEdit() {
      this.editingEmailId = null;
      this.emailInput = "";
    },
    async saveEmail(userId) {
      // The update endpoint takes the identity pair; keep the name unchanged.
      const u = this.users.find((x) => x.id === userId);
      try {
        const res = await fetch("/api/v1/users/" + userId, {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ name: u ? u.name : "", email: this.emailInput }),
        });
        if (!res.ok) {
          const b = await res.text();
          throw new Error("HTTP " + res.status + (b ? " — " + b.slice(0, 120) : ""));
        }
        await this.load();
        this.cancelEmailEdit();
      } catch (e) {
        this.error = t("users.error_email", { error: e.message });
      }
    },

    /**
     * Access deadline (#53). A date prompt rather than a modal: it is a rare,
     * single-value edit, and the list already carries every other per-user
     * action inline.
     */
    async startValidUntilEdit(u) {
      const current = u.validUntil ? u.validUntil.slice(0, 10) : "";
      const answer = window.prompt(t("users.valid_until_prompt"), current);
      if (answer === null) return;   // cancelled

      let payload;
      const trimmed = answer.trim();
      if (trimmed === "") {
        payload = { validUntil: null };   // clearing = no expiry
      } else if (/^\d{4}-\d{2}-\d{2}$/.test(trimmed)) {
        // End of the chosen day in the browser's own zone, so "valid until the
        // 31st" means through the 31st rather than expiring at midnight as it
        // starts.
        const end = new Date(trimmed + "T23:59:59");
        if (isNaN(end.getTime())) { this.error = t("users.valid_until_invalid"); return; }
        payload = { validUntil: end.toISOString() };
      } else {
        this.error = t("users.valid_until_invalid");
        return;
      }

      try {
        const res = await fetch("/api/v1/users/" + u.id + "/valid-until", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(payload),
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        this.error = t("users.valid_until_error", { error: e.message });
      }
    },

    startPasswordEdit(u) {
      this.editingPasswordId = u.id;
      this.passwordInput = "";
    },
    cancelPasswordEdit() {
      this.editingPasswordId = null;
      this.passwordInput = "";
    },
    async savePassword(userId) {
      try {
        const res = await fetch("/api/v1/users/" + userId + "/password", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ password: this.passwordInput }),
        });
        if (!res.ok) {
          const b = await res.text();
          throw new Error("HTTP " + res.status + (b ? " — " + b.slice(0, 120) : ""));
        }
        await this.load();
        this.cancelPasswordEdit();
      } catch (e) {
        this.error = t("users.error_password", { error: e.message });
      }
    },

    formatDate(iso) { return formatDate(iso); },

    async openGwsDialog() {
      this.gwsOpen = true;
      this.gwsResult = null;
      this.gwsError = null;
      this.gwsSelected = new Set();
      await this.loadGwsPreview();
    },
    closeGwsDialog() {
      this.gwsOpen = false;
      this.gwsUsers = [];
      this.gwsResult = null;
      this.gwsError = null;
    },
    async loadGwsPreview() {
      this.gwsLoading = true;
      this.gwsError = null;
      try {
        const res = await fetch("/api/v1/users/import/google");
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || "HTTP " + res.status);
        this.gwsConfigured = data.configured;
        this.gwsUsers = data.users || [];
      } catch (e) {
        this.gwsError = t("users.gws_error", { error: e.message });
      } finally {
        this.gwsLoading = false;
      }
    },
    toggleGwsUser(email) {
      if (this.gwsSelected.has(email)) {
        this.gwsSelected.delete(email);
      } else {
        this.gwsSelected.add(email);
      }
      // Trigger reactivity — Vue 3 doesn't observe Set mutations automatically
      this.gwsSelected = new Set(this.gwsSelected);
    },
    selectAllNew() {
      const newEmails = this.gwsUsers.filter(u => u.status === "new").map(u => u.email);
      this.gwsSelected = new Set(newEmails);
    },
    async importGwsSelected() {
      if (this.gwsSelected.size === 0) {
        alert(t("users.gws_none_selected"));
        return;
      }
      this.gwsImporting = true;
      try {
        const res = await fetch("/api/v1/users/import/google", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ emails: [...this.gwsSelected] }),
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || "HTTP " + res.status);
        this.gwsResult = data;
        this.gwsSelected = new Set();
        await Promise.all([this.load(), this.loadGwsPreview()]);
      } catch (e) {
        this.gwsError = t("users.gws_error", { error: e.message });
      } finally {
        this.gwsImporting = false;
      }
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('users.title') }}
        <span v-if="users.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">
          {{ filteredUsers.length }}<template v-if="quickFilter.trim()"> / {{ users.length }}</template>
        </span>
      </h1>
      <div style="display: flex; gap: var(--space-2)">
        <input ref="searchInput" class="input input-sm" type="search" v-model="quickFilter"
               :placeholder="t('users.quickfilter_ph')" style="width: 220px" />
        <button v-if="googleWsAvailable" class="btn btn-ghost btn-sm" @click="openGwsDialog">
          <Icon name="users" :size="13" />{{ t('users.gws_btn') }}
        </button>
      </div>
    </div>

    <!-- Google Workspace Import Dialog -->
    <div v-if="gwsOpen" style="position: fixed; inset: 0; background: rgba(0,0,0,.45); z-index: 200; display: flex; align-items: center; justify-content: center; padding: var(--space-4)">
      <div class="card" style="width: 100%; max-width: 680px; max-height: 80vh; display: flex; flex-direction: column; overflow: hidden">
        <div style="display: flex; align-items: center; justify-content: space-between; padding: var(--space-4) var(--space-5); border-bottom: 1px solid var(--border)">
          <h2 style="margin: 0; font-size: var(--text-md)">{{ t('users.gws_title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeGwsDialog">✕</button>
        </div>

        <div style="flex: 1; overflow-y: auto; padding: var(--space-4) var(--space-5)">
          <div v-if="gwsLoading" class="muted">{{ t('users.gws_loading') }}</div>
          <div v-else-if="gwsError" class="error-banner">{{ gwsError }}</div>
          <div v-else-if="!gwsConfigured" class="callout callout-warning">{{ t('users.gws_not_configured') }}</div>
          <div v-else-if="gwsUsers.length === 0" class="muted">{{ t('users.gws_empty') }}</div>
          <template v-else>
            <div v-if="gwsResult" style="margin-bottom: var(--space-3); color: var(--status-ok); font-size: var(--text-sm)">
              {{ t('users.gws_result', { imported: gwsResult.imported, skipped: gwsResult.skipped }) }}
              <span v-if="gwsResult.errors && gwsResult.errors.length" style="color: var(--status-warn)">
                {{ t('users.gws_result_errors', { n: gwsResult.errors.length }) }}
              </span>
            </div>
            <div style="margin-bottom: var(--space-3)">
              <button class="btn btn-ghost btn-sm" @click="selectAllNew">{{ t('users.gws_select_all_new') }}</button>
            </div>
            <table class="table">
              <thead>
                <tr>
                  <th style="width: 32px"></th>
                  <th>{{ t('users.gws_th_name') }}</th>
                  <th>{{ t('users.gws_th_email') }}</th>
                  <th>{{ t('users.gws_th_status') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="u in gwsUsers" :key="u.email"
                    :style="u.status !== 'new' ? 'opacity: 0.5' : ''"
                    @click="u.status === 'new' && toggleGwsUser(u.email)"
                    style="cursor: pointer">
                  <td>
                    <input type="checkbox"
                           :checked="gwsSelected.has(u.email)"
                           :disabled="u.status !== 'new'"
                           @change.stop="toggleGwsUser(u.email)"
                           @click.stop />
                  </td>
                  <td>{{ u.name || '—' }}</td>
                  <td class="mono">{{ u.email }}</td>
                  <td>
                    <span :class="['badge', u.status === 'new' ? 'badge-success' : u.status === 'suspended' ? 'badge-neutral' : 'badge-info']">
                      {{ t('users.gws_status_' + u.status) }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </template>
        </div>

        <div style="display: flex; gap: var(--space-3); padding: var(--space-4) var(--space-5); border-top: 1px solid var(--border); justify-content: flex-end">
          <button class="btn btn-ghost btn-sm" @click="closeGwsDialog">{{ t('users.gws_btn_close') }}</button>
          <button v-if="gwsConfigured && gwsUsers.length > 0"
                  class="btn btn-primary btn-sm"
                  :disabled="gwsImporting || gwsSelected.size === 0"
                  @click="importGwsSelected">
            {{ gwsImporting ? t('users.gws_importing') : t('users.gws_btn_import') }}
            <span v-if="gwsSelected.size > 0" class="mono" style="margin-left: 4px">({{ gwsSelected.size }})</span>
          </button>
        </div>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="users.length === 0" class="empty-state">
      <h2>{{ t('users.empty_title') }}</h2>
      <p>{{ t('users.empty_desc') }}</p>
    </div>

    <div v-else-if="filteredUsers.length === 0" class="muted" style="padding: var(--space-4) 0">
      {{ t('users.empty_filtered', { query: quickFilter.trim() }) }}
    </div>

    <table v-else class="table">
      <thead>
        <tr>
          <th>{{ t('users.th_name') }}</th>
          <th>{{ t('users.th_email') }}</th>
          <th>{{ t('users.th_role') }}</th>
          <th>{{ t('users.th_status') }}</th>
          <th>{{ t('users.th_peers') }}</th>
          <th>{{ t('users.th_created') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="u in filteredUsers" :key="u.id" :style="!u.enabled ? 'opacity: 0.55' : ''">
          <td>
            <span style="display: inline-flex; align-items: center; gap: var(--space-2)">
              <Avatar :user="u" :size="32" />
              <span v-if="editingNicknameId !== u.id" style="display: inline-flex; align-items: center; gap: var(--space-1); flex-wrap: nowrap">
                <span>{{ u.displayName }}</span>
                <span v-if="u.nickname" class="muted mono" style="font-size: var(--text-xs)">({{ u.name }})</span>
                <button @click="startNicknameEdit(u)" class="btn btn-ghost btn-sm" style="padding: 2px 6px; flex-shrink: 0" :title="t('users.btn_nickname')">
                  <Icon name="edit" :size="12" />
                </button>
              </span>
              <span v-else style="display: inline-flex; align-items: center; gap: var(--space-2)">
                <input class="input" style="width: 140px; height: 28px; font-size: var(--text-sm); padding: 0 8px"
                       v-model="nicknameInput"
                       :placeholder="u.name"
                       @keyup.enter="saveNickname(u.id)"
                       @keyup.escape="cancelNicknameEdit"
                       autofocus />
                <button @click="saveNickname(u.id)" class="btn btn-primary btn-sm" style="height: 28px">✓</button>
                <button @click="cancelNicknameEdit" class="btn btn-ghost btn-sm" style="height: 28px">✕</button>
              </span>
            </span>
          </td>
          <td class="mono">
            <span v-if="editingEmailId !== u.id" style="display: inline-flex; align-items: center; gap: var(--space-1)">
              <span>{{ u.email }}</span>
              <button @click="startEmailEdit(u)" class="btn btn-ghost btn-sm" style="padding: 2px 6px; flex-shrink: 0" :title="t('users.btn_email')">
                <Icon name="edit" :size="12" />
              </button>
            </span>
            <span v-else style="display: inline-flex; align-items: center; gap: var(--space-2)">
              <input class="input" type="email" style="width: 190px; height: 28px; font-size: var(--text-sm); padding: 0 8px"
                     v-model="emailInput"
                     @keyup.enter="saveEmail(u.id)"
                     @keyup.escape="cancelEmailEdit"
                     autofocus />
              <button @click="saveEmail(u.id)" class="btn btn-primary btn-sm" style="height: 28px">✓</button>
              <button @click="cancelEmailEdit" class="btn btn-ghost btn-sm" style="height: 28px">✕</button>
            </span>
          </td>
          <td>
            <span :class="['badge', u.isAdmin ? 'badge-info' : 'badge-neutral']">
              {{ u.isAdmin ? t('users.role_admin') : t('users.role_user') }}
            </span>
          </td>
          <td>
            <!-- Expiry is shown ahead of enabled: a user can be "enabled" and
                 still have no access because their window closed (#53), and
                 the badge must not claim otherwise. -->
            <span v-if="u.accessExpired" class="badge badge-warning" :title="formatDate(u.validUntil)">
              <Icon name="clock" :size="12" />{{ t('users.status_expired') }}
            </span>
            <span v-else :class="['badge', u.enabled ? 'badge-success' : 'badge-neutral']">
              {{ u.enabled ? t('users.status_active') : t('users.status_disabled') }}
            </span>
            <div v-if="u.validUntil && !u.accessExpired" class="muted" style="font-size: var(--text-xs); margin-top: 2px">
              {{ t('users.valid_until_hint', { date: formatDate(u.validUntil) }) }}
            </div>
          </td>
          <td>
            <button class="btn btn-ghost btn-sm" @click="$router.push({ name: 'peers', query: {} })" style="font-family: var(--font-mono); padding: 2px 8px">
              <Icon name="peers" :size="13" />{{ u.peerCount }}
            </button>
          </td>
          <td class="muted">{{ formatDate(u.createdAt) }}</td>
          <td style="text-align: right">
            <template v-if="editingPasswordId !== u.id">
              <router-link
                :to="{ name: 'my-access', query: { as: u.id, asName: u.displayName } }"
                class="btn btn-ghost btn-sm">
                <Icon name="external-link" :size="13" />{{ t('users.btn_view') }}
              </router-link>
              <button class="btn btn-ghost btn-sm" @click="toggleAdmin(u)">
                <Icon :name="u.isAdmin ? 'shield-off' : 'shield'" :size="13" />
                {{ u.isAdmin ? t('users.btn_revoke_admin') : t('users.btn_make_admin') }}
              </button>
              <button class="btn btn-ghost btn-sm" @click="toggleEnabled(u)">
                <Icon :name="u.enabled ? 'pause-circle' : 'play-circle'" :size="13" />
                {{ u.enabled ? t('users.btn_disable') : t('users.btn_enable') }}
              </button>
              <button class="btn btn-ghost btn-sm" @click="startValidUntilEdit(u)">
                <Icon name="clock" :size="13" />{{ t('users.btn_valid_until') }}
              </button>
              <button class="btn btn-ghost btn-sm" @click="startPasswordEdit(u)"><Icon name="edit" :size="13" />{{ t('users.btn_password') }}</button>
              <button class="btn btn-secondary btn-sm" @click="openCreatePeer(u.id)"><Icon name="peers" :size="13" />{{ t('users.btn_add_peer') }}</button>
              <button class="btn btn-ghost btn-sm" @click="deleteUser(u.id)"><Icon name="trash" :size="13" />{{ t('users.btn_delete') }}</button>
            </template>
            <span v-else style="display: inline-flex; align-items: center; gap: var(--space-2); justify-content: flex-end">
              <input class="input" type="password" style="width: 170px; height: 28px; font-size: var(--text-sm); padding: 0 8px"
                     v-model="passwordInput"
                     :placeholder="t('users.password_placeholder')"
                     @keyup.enter="savePassword(u.id)"
                     @keyup.escape="cancelPasswordEdit"
                     autofocus />
              <button @click="savePassword(u.id)" class="btn btn-primary btn-sm" style="height: 28px">✓</button>
              <button @click="cancelPasswordEdit" class="btn btn-ghost btn-sm" style="height: 28px">✕</button>
            </span>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="page-header" style="margin-top: var(--space-8)">
      <h2 style="font-size: var(--text-lg); font-weight: 600; margin: 0">{{ t('users.create_title') }}</h2>
    </div>
    <form
      class="card card-pad"
      style="max-width: 480px"
      @submit.prevent="createUser"
    >
      <div class="field">
        <label for="name">{{ t('users.field_name') }}</label>
        <input id="name" class="input" v-model="newUser.name" required :placeholder="t('users.field_name_ph')" />
      </div>
      <div class="field" style="margin-bottom: var(--space-5)">
        <label for="newEmail">{{ t('users.field_email') }}</label>
        <input
          id="newEmail"
          class="input"
          type="email"
          v-model="newUser.email"
          required
          :placeholder="t('users.field_email_ph')"
        />
      </div>
      <button type="submit" class="btn btn-primary" :disabled="submitting">
        {{ submitting ? t('users.btn_creating') : t('users.btn_create') }}
      </button>
    </form>

    ${peerModalTemplate}
  `,
});
