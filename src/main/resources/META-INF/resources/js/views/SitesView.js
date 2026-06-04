import { defineComponent } from "vue";
import { t, locale } from "/js/i18n.js";

// Sites = organisational grouping for resources. The CIDR is informational,
// rendered next to the name; it does NOT participate in nftables rules.
// Resources live under each site and are managed via ResourcesView.
export default defineComponent({
  name: "SitesView",
  data() {
    return {
      sites: [],
      loading: true,
      error: null,
      modal: null,        // null | "create" | "edit"
      form: { name: "", cidr: "", description: "" },
      editId: null,
      submitting: false,
      formError: null,
      lang: locale.current,
    };
  },
  async mounted() {
    await this.load();
  },
  computed: { _lang() { return locale.current; } },
  methods: {
    t(key, vars) { return t(key, vars); },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/sites");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.sites = await res.json();
      } catch (e) {
        this.error = t("sites.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    openCreate() {
      this.modal = "create";
      this.editId = null;
      this.form = { name: "", cidr: "", description: "" };
      this.formError = null;
    },
    openEdit(site) {
      this.modal = "edit";
      this.editId = site.id;
      this.form = { name: site.name, cidr: site.cidr, description: site.description || "" };
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
        const url = this.editId ? "/api/v1/sites/" + this.editId : "/api/v1/sites";
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
        await this.load();
        this.closeModal();
      } catch (e) {
        this.formError = t("sites.error_save", { error: e.message });
      } finally {
        this.submitting = false;
      }
    },
    async deleteSite(site) {
      if (site.resourceCount > 0) {
        alert(t("sites.confirm_res", { n: site.resourceCount }));
        return;
      }
      if (!confirm(t("sites.confirm_del", { name: site.name }))) return;
      try {
        const res = await fetch("/api/v1/sites/" + site.id, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        this.error = t("sites.error_delete", { error: e.message });
      }
    },
    goToResources(site) {
      this.$router.push({ name: "resources", params: { siteId: site.id } });
    },
    formatDate(iso) {
      return iso ? new Date(iso).toLocaleString("de-DE") : "—";
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('sites.title') }} <span v-if="sites.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">{{ sites.length }}</span></h1>
      <button class="btn btn-primary btn-sm" @click="openCreate">{{ t('sites.create_btn') }}</button>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="sites.length === 0" class="empty-state">
      <h2>{{ t('sites.empty_title') }}</h2>
      <p>{{ t('sites.empty_desc') }}</p>
    </div>

    <table v-else class="table">
      <thead>
        <tr>
          <th>{{ t('sites.th_name') }}</th>
          <th>{{ t('sites.th_cidr') }}</th>
          <th>{{ t('sites.th_desc') }}</th>
          <th>{{ t('sites.th_resources') }}</th>
          <th>{{ t('sites.th_created') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in sites" :key="s.id">
          <td>{{ s.name }}</td>
          <td class="mono">{{ s.cidr }}</td>
          <td class="muted">{{ s.description || "—" }}</td>
          <td>
            <button class="btn btn-ghost btn-sm" @click="goToResources(s)">
              {{ s.resourceCount }} <span style="margin-left: 4px">→</span>
            </button>
          </td>
          <td class="muted">{{ formatDate(s.createdAt) }}</td>
          <td style="text-align: right">
            <button class="btn btn-ghost btn-sm" @click="openEdit(s)">{{ t('sites.btn_edit') }}</button>
            <button class="btn btn-ghost btn-sm" @click="deleteSite(s)">{{ t('sites.btn_delete') }}</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="modal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ modal === 'create' ? t('sites.modal_create') : t('sites.modal_edit') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
        </div>
        <form @submit.prevent="submit">
          <div class="modal-body">
            <div v-if="formError" class="error-banner">{{ formError }}</div>
            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="siteName">{{ t('sites.field_name') }}</label>
              <input id="siteName" class="input" v-model="form.name" required :placeholder="t('sites.field_name_ph')" />
            </div>
            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="siteCidr">{{ t('sites.field_cidr') }}</label>
              <input id="siteCidr" class="input mono" v-model="form.cidr" required placeholder="10.20.0.0/16" />
              <div class="field-hint">Das Netz hinter dem Standort. Nur informativ — wird nicht enforced.</div>
            </div>
            <div class="field">
              <label for="siteDesc">{{ t('sites.field_desc') }}</label>
              <textarea id="siteDesc" class="textarea" rows="2" v-model="form.description" placeholder="Optional"></textarea>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="closeModal">{{ t('common.cancel') }}</button>
            <button type="submit" class="btn btn-primary" :disabled="submitting">
              {{ submitting ? t('sites.btn_saving') : t('sites.btn_save') }}
            </button>
          </div>
        </form>
      </div>
    </div>
  `,
});
