import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";

// Port-group templates (admin-managed). A group is a named bundle of
// (port, transport, protocol, label) tuples that the admin can apply to a
// resource in one click. Snapshot semantics: editing a group later does NOT
// change resources where it was previously applied.
export default defineComponent({
  name: "PortGroupsView",
  components: { Icon },
  data() {
    return {
      lang: locale.current,
      groups: [],
      loading: true,
      error: null,
      // Edit modal — also used for create. modal='create' or 'edit'.
      modal: null,
      editId: null,
      form: { name: "", description: "", members: [] },
      submitting: false,
      formError: null,
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
        const res = await fetch("/api/v1/port-groups");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.groups = await res.json();
      } catch (e) {
        this.error = t("portgroups.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    openCreate() {
      this.modal = "create";
      this.editId = null;
      this.form = { name: "", description: "", members: [this.emptyMember()] };
      this.formError = null;
    },
    openEdit(g) {
      this.modal = "edit";
      this.editId = g.id;
      this.form = {
        name: g.name,
        description: g.description || "",
        members: g.members.map((m) => ({
          port: String(m.port),
          transport: m.transport,
          protocol: m.protocol,
          label: m.label || "",
        })),
      };
      if (this.form.members.length === 0) this.form.members.push(this.emptyMember());
      this.formError = null;
    },
    closeModal() {
      this.modal = null;
      this.editId = null;
      this.formError = null;
    },
    emptyMember() {
      return { port: "", transport: "tcp", protocol: "", label: "" };
    },
    addMemberRow() {
      this.form.members.push(this.emptyMember());
    },
    removeMemberRow(i) {
      this.form.members.splice(i, 1);
      if (this.form.members.length === 0) this.form.members.push(this.emptyMember());
    },
    async submit() {
      this.submitting = true;
      this.formError = null;
      try {
        // Strip empty rows + coerce port to int. Backend validates the rest.
        const members = this.form.members
            .filter((m) => m.port && m.protocol)
            .map((m) => ({
              port: parseInt(m.port, 10),
              transport: m.transport,
              protocol: m.protocol,
              label: m.label || null,
            }));
        if (members.length === 0) {
          this.formError = t("portgroups.err_min_port");
          return;
        }
        const body = JSON.stringify({
          name: this.form.name.trim(),
          description: this.form.description || null,
          members,
        });
        const url = this.editId ? "/api/v1/port-groups/" + this.editId : "/api/v1/port-groups";
        const method = this.editId ? "PUT" : "POST";
        const res = await fetch(url, {
          method,
          headers: { "content-type": "application/json" },
          body,
        });
        if (!res.ok) {
          const errBody = await res.text();
          throw new Error("HTTP " + res.status + (errBody ? " — " + errBody.slice(0, 200) : ""));
        }
        await this.load();
        this.closeModal();
      } catch (e) {
        this.formError = t("portgroups.error_save", { error: e.message });
      } finally {
        this.submitting = false;
      }
    },
    async deleteGroup(g) {
      if (!confirm(t("portgroups.confirm_del", { name: g.name }))) return;
      try {
        const res = await fetch("/api/v1/port-groups/" + g.id, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        this.error = t("portgroups.error_delete", { error: e.message });
      }
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('portgroups.title') }} <span v-if="groups.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">{{ groups.length }}</span></h1>
      <button class="btn btn-primary btn-sm" @click="openCreate">{{ t('portgroups.create_btn') }}</button>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="groups.length === 0" class="empty-state">
      <h2>{{ t('portgroups.empty_title') }}</h2>
      <p>{{ t('portgroups.empty_desc') }}</p>
    </div>

    <table v-else class="table">
      <thead>
        <tr>
          <th>{{ t('portgroups.th_name') }}</th>
          <th>{{ t('portgroups.th_desc') }}</th>
          <th>{{ t('portgroups.th_ports') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="g in groups" :key="g.id">
          <td>{{ g.name }}</td>
          <td class="muted">{{ g.description || "—" }}</td>
          <td>
            <div style="display: flex; flex-wrap: wrap; gap: 4px">
              <span v-for="m in g.members" :key="m.id" class="badge badge-neutral">
                <span class="mono">{{ m.port }}/{{ m.transport }}</span>
                <span style="margin-left: 6px">{{ m.protocol }}</span>
              </span>
              <span v-if="g.members.length === 0" class="muted" style="font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">{{ t('portgroups.empty_ports') }}</span>
            </div>
          </td>
          <td style="text-align: right">
            <button class="btn btn-ghost btn-sm" @click="openEdit(g)"><Icon name="edit" :size="13" />{{ t('portgroups.btn_edit') }}</button>
            <button class="btn btn-ghost btn-sm" @click="deleteGroup(g)"><Icon name="trash" :size="13" />{{ t('portgroups.btn_delete') }}</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="modal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal modal-lg">
        <div class="modal-header">
          <h2>{{ modal === 'create' ? t('portgroups.modal_create') : t('portgroups.modal_edit') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
        </div>
        <form @submit.prevent="submit">
          <div class="modal-body">
            <div v-if="formError" class="error-banner">{{ formError }}</div>

            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="pgName">{{ t('portgroups.field_name') }}</label>
              <input id="pgName" class="input" v-model="form.name" required :placeholder="t('portgroups.field_name_ph')" />
            </div>

            <div class="field" style="margin-bottom: var(--space-5)">
              <label for="pgDesc">{{ t('portgroups.field_desc') }}</label>
              <textarea id="pgDesc" class="textarea" rows="2" v-model="form.description" :placeholder="t('portgroups.field_desc_ph')"></textarea>
            </div>

            <div class="field-hint" style="margin-bottom: var(--space-3); font-family: var(--font-sans); text-transform: none; letter-spacing: 0; font-size: var(--text-sm)">{{ t('portgroups.ports_hint') }}</div>
            <div style="display: flex; flex-direction: column; gap: var(--space-2)">
              <div v-for="(m, i) in form.members" :key="i"
                   style="display: grid; grid-template-columns: 100px 100px 160px 1fr auto; gap: var(--space-3); align-items: center">
                <input class="input mono" type="number" min="1" max="65535" v-model="m.port" :placeholder="t('portgroups.port_ph')" />
                <select class="select" v-model="m.transport">
                  <option value="tcp">tcp</option>
                  <option value="udp">udp</option>
                </select>
                <select class="select" v-model="m.protocol">
                  <option value="">{{ t('portgroups.protocol_ph') }}</option>
                  <option>RDP</option>
                  <option>SSH</option>
                  <option>SFTP</option>
                  <option>HTTP</option>
                  <option>HTTPS</option>
                  <option>SMB</option>
                  <option>X11</option>
                  <option>RAW</option>
                  <option>IPP</option>
                  <option>CUSTOM</option>
                </select>
                <input class="input" v-model="m.label" :placeholder="t('portgroups.label_ph')" />
                <button type="button" class="btn btn-ghost btn-sm" @click="removeMemberRow(i)" title="Zeile entfernen">✕</button>
              </div>
              <button type="button" class="btn btn-ghost btn-sm" @click="addMemberRow" style="align-self: flex-start">{{ t('portgroups.btn_add_port') }}</button>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="closeModal">{{ t('common.cancel') }}</button>
            <button type="submit" class="btn btn-primary" :disabled="submitting">
              {{ submitting ? t('portgroups.btn_saving') : t('portgroups.btn_save') }}
            </button>
          </div>
        </form>
      </div>
    </div>
  `,
});
