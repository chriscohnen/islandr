import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";

// Roles list + per-role membership editor. Grants for a role live in the
// matrix view; this view focuses on "what is this role and who is in it".
export default defineComponent({
  name: "RolesView",
  components: { Icon },
  data() {
    return {
      roles: [],
      allUsers: [],
      loading: true,
      error: null,
      // Create/edit role modal
      modal: null,
      form: { name: "", description: "" },
      editId: null,
      submitting: false,
      formError: null,
      // Members modal
      membersModal: null,    // role object or null
      checkedIds: new Set(),
      membersError: null,
      lang: locale.current,
    };
  },
  async mounted() {
    await Promise.all([this.loadRoles(), this.loadUsers()]);
  },
  computed: { _lang() { return locale.current; } },
  methods: {
    t(key, vars) { return t(key, vars); },
    async loadRoles() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/roles");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.roles = await res.json();
      } catch (e) {
        this.error = t("roles.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    async loadUsers() {
      try {
        const res = await fetch("/api/v1/users");
        if (res.ok) this.allUsers = await res.json();
      } catch {}
    },
    openCreate() {
      this.modal = "create";
      this.editId = null;
      this.form = { name: "", description: "" };
      this.formError = null;
    },
    openEdit(role) {
      this.modal = "edit";
      this.editId = role.id;
      this.form = { name: role.name, description: role.description || "" };
      this.formError = null;
    },
    closeModal() {
      this.modal = null;
      this.editId = null;
      this.formError = null;
    },
    async submit() {
      this.submitting = true;
      this.formError = null;
      try {
        const url = this.editId ? "/api/v1/roles/" + this.editId : "/api/v1/roles";
        const method = this.editId ? "PUT" : "POST";
        const res = await fetch(url, {
          method,
          headers: { "content-type": "application/json" },
          body: JSON.stringify(this.form),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        await this.loadRoles();
        this.closeModal();
      } catch (e) {
        this.formError = t("roles.error_save", { error: e.message });
      } finally {
        this.submitting = false;
      }
    },
    async deleteRole(role) {
      if (role.grantCount > 0) {
        if (!confirm(t("roles.confirm_delete_grants", { n: role.grantCount }))) return;
      } else {
        if (!confirm(t("roles.confirm_delete", { name: role.name }))) return;
      }
      try {
        const res = await fetch("/api/v1/roles/" + role.id, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.loadRoles();
      } catch (e) {
        this.error = t("roles.error_delete", { error: e.message });
      }
    },
    async openMembers(role) {
      this.membersModal = role;
      this.membersError = null;
      this.checkedIds = new Set();
      try {
        const res = await fetch("/api/v1/roles/" + role.id + "/users");
        if (res.ok) {
          const list = await res.json();
          this.checkedIds = new Set(list.map((u) => u.id));
        }
      } catch (e) {
        this.membersError = t("roles.error_load", { error: e.message });
      }
    },
    closeMembers() {
      this.membersModal = null;
      this.checkedIds = new Set();
      this.membersError = null;
    },
    toggleMember(userId) {
      const next = new Set(this.checkedIds);
      if (next.has(userId)) next.delete(userId); else next.add(userId);
      this.checkedIds = next;
    },
    async saveMembers() {
      if (!this.membersModal) return;
      try {
        const res = await fetch("/api/v1/roles/" + this.membersModal.id + "/users", {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ userIds: Array.from(this.checkedIds) }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        await this.loadRoles();
        this.closeMembers();
      } catch (e) {
        this.membersError = t("roles.error_save", { error: e.message });
      }
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('roles.title') }} <span v-if="roles.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">{{ roles.length }}</span></h1>
      <button class="btn btn-primary btn-sm" @click="openCreate">{{ t('roles.create_btn') }}</button>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="roles.length === 0" class="empty-state">
      <h2>{{ t('roles.empty_title') }}</h2>
      <p>{{ t('roles.empty_desc') }}</p>
    </div>

    <table v-else class="table">
      <thead>
        <tr>
          <th>{{ t('roles.th_name') }}</th>
          <th>{{ t('roles.th_desc') }}</th>
          <th>{{ t('roles.th_members') }}</th>
          <th>{{ t('roles.th_grants') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in roles" :key="r.id">
          <td>
            {{ r.name }}
            <span v-if="r.autoAll" class="tag" style="margin-left: var(--space-2)">{{ t('roles.auto_all_badge') }}</span>
          </td>
          <td class="muted">{{ r.description || "—" }}</td>
          <td>
            <span v-if="r.autoAll" class="muted">{{ t('roles.all_members') }}</span>
            <button v-else class="btn btn-ghost btn-sm" @click="openMembers(r)">
              <Icon name="users" :size="13" />{{ r.memberCount }}
            </button>
          </td>
          <td>
            <router-link to="/acl" class="btn btn-ghost btn-sm"><Icon name="acl" :size="13" />{{ r.grantCount }}</router-link>
          </td>
          <td style="text-align: right">
            <!-- The Everyone (auto_all) role is protected (ADR-0013) — no edit/delete. -->
            <span v-if="r.autoAll" class="muted" style="font-size: var(--text-xs)">{{ t('roles.protected') }}</span>
            <template v-else>
              <button class="btn btn-ghost btn-sm" @click="openEdit(r)"><Icon name="edit" :size="13" />{{ t('roles.btn_edit') }}</button>
              <button class="btn btn-ghost btn-sm" @click="deleteRole(r)"><Icon name="trash" :size="13" />{{ t('roles.btn_delete') }}</button>
            </template>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="modal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ modal === 'create' ? t('roles.modal_create') : t('roles.modal_edit') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
        </div>
        <form @submit.prevent="submit">
          <div class="modal-body">
            <div v-if="formError" class="error-banner">{{ formError }}</div>
            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="roleName">{{ t('roles.field_name') }}</label>
              <input id="roleName" class="input" v-model="form.name" required :placeholder="t('roles.field_name_ph')" />
            </div>
            <div class="field">
              <label for="roleDesc">{{ t('roles.field_desc') }}</label>
              <textarea id="roleDesc" class="textarea" rows="2" v-model="form.description" :placeholder="t('roles.field_desc_ph')"></textarea>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="closeModal">{{ t('common.cancel') }}</button>
            <button type="submit" class="btn btn-primary" :disabled="submitting">
              {{ submitting ? t('roles.btn_saving') : t('roles.btn_save') }}
            </button>
          </div>
        </form>
      </div>
    </div>

    <div v-if="membersModal" class="modal-backdrop" @click.self="closeMembers">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ t('roles.field_users') }} "{{ membersModal.name }}"</h2>
          <button class="btn btn-ghost btn-sm" @click="closeMembers">✕</button>
        </div>
        <div class="modal-body">
          <div v-if="membersError" class="error-banner">{{ membersError }}</div>
          <div v-if="allUsers.length === 0" class="muted">{{ t('roles.empty_title') }}</div>
          <div v-else style="display: flex; flex-direction: column; gap: var(--space-2)">
            <label v-for="u in allUsers" :key="u.id"
                   style="display: flex; align-items: center; gap: var(--space-3); cursor: pointer; padding: var(--space-2); border-radius: var(--radius-sm); text-transform: none; letter-spacing: 0; font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 400">
              <input type="checkbox" :checked="checkedIds.has(u.id)" @change="toggleMember(u.id)" style="width: 16px; height: 16px; accent-color: var(--accent)" />
              <div>
                <div>{{ u.name }}</div>
                <div class="mono muted" style="font-size: var(--text-xs)">{{ u.email }}</div>
              </div>
            </label>
          </div>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-ghost" @click="closeMembers">{{ t('common.cancel') }}</button>
          <button type="button" class="btn btn-primary" @click="saveMembers">{{ t('roles.btn_save') }}</button>
        </div>
      </div>
    </div>
  `,
});
