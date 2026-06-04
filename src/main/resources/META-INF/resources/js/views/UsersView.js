import { defineComponent } from "vue";
import { peerModalMixin, peerModalTemplate } from "/js/peerModal.js";
import Avatar from "/js/Avatar.js";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";

// User management. Each row gets a "+ Peer" button that opens the shared
// peer-create modal. The full peer list across all users lives in PeersView.
export default defineComponent({
  name: "UsersView",
  props: {
    retention: { type: String, default: "never" },
  },
  mixins: [peerModalMixin],
  components: { Avatar, Icon },
  data() {
    return {
      users: [],
      loading: true,
      error: null,
      newUser: { name: "", email: "" },
      submitting: false,
      lang: locale.current,
      editingNicknameId: null,
      nicknameInput: "",
    };
  },
  computed: {
    _lang() { return locale.current; },
    modalUserName() {
      const u = this.users.find((x) => x.id === this.modalUserId);
      return u ? `${u.displayName} (${u.email})` : null;
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

    formatDate(iso) {
      if (!iso) return "";
      return new Date(iso).toLocaleString("de-DE");
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('users.title') }} <span v-if="users.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">{{ users.length }}</span></h1>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="users.length === 0" class="empty-state">
      <h2>{{ t('users.empty_title') }}</h2>
      <p>{{ t('users.empty_desc') }}</p>
    </div>

    <table v-else class="table">
      <thead>
        <tr>
          <th>{{ t('users.th_name') }}</th>
          <th>{{ t('users.th_email') }}</th>
          <th>{{ t('users.th_role') }}</th>
          <th>{{ t('users.th_status') }}</th>
          <th>{{ t('users.th_created') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="u in users" :key="u.id">
          <td>
            <span style="display: inline-flex; align-items: center; gap: var(--space-2)">
              <Avatar :user="u" :size="32" />
              <span v-if="editingNicknameId !== u.id">
                <span>{{ u.displayName }}</span>
                <span v-if="u.nickname" class="muted mono" style="font-size: var(--text-xs); margin-left: 4px">({{ u.name }})</span>
                <button @click="startNicknameEdit(u)" class="btn btn-ghost btn-sm" style="padding: 2px 6px; margin-left: 4px" :title="t('users.btn_nickname')">
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
          <td class="mono">{{ u.email }}</td>
          <td>
            <span :class="['badge', u.isAdmin ? 'badge-info' : 'badge-neutral']">
              {{ u.isAdmin ? t('users.role_admin') : t('users.role_user') }}
            </span>
          </td>
          <td>
            <span :class="['badge', u.enabled ? 'badge-success' : 'badge-neutral']">
              {{ u.enabled ? t('users.status_active') : t('users.status_disabled') }}
            </span>
          </td>
          <td class="muted">{{ formatDate(u.createdAt) }}</td>
          <td style="text-align: right">
            <router-link
              :to="{ name: 'my-access', query: { as: u.id, asName: u.displayName } }"
              class="btn btn-ghost btn-sm"
              title="Benutzer-Ansicht öffnen">
              {{ t('users.btn_view') }}
            </router-link>
            <button class="btn btn-ghost btn-sm" @click="toggleAdmin(u)">
              {{ u.isAdmin ? t('users.btn_revoke_admin') : t('users.btn_make_admin') }}
            </button>
            <button class="btn btn-secondary btn-sm" @click="openCreatePeer(u.id)">{{ t('users.btn_add_peer') }}</button>
            <button class="btn btn-ghost btn-sm" @click="deleteUser(u.id)">{{ t('users.btn_delete') }}</button>
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
