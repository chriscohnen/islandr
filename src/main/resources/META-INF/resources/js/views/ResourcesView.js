import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";

// Resources of a single site. The site is passed via route param :siteId.
// Each resource has a list of ports (port + transport + protocol-label).
// Ports are managed inline (add/remove inside the row), not via a separate modal.
export default defineComponent({
  name: "ResourcesView",
  components: { Icon },
  props: {
    siteId: { type: String, required: true },
  },
  data() {
    return {
      lang: locale.current,
      site: null,
      resources: [],
      loading: true,
      error: null,
      // Create/edit resource modal
      modal: null,
      form: { name: "", ip: "", description: "", type: "computer" },
      editId: null,
      submitting: false,
      formError: null,
      // Inline port form (one per resource at a time)
      portFormFor: null,
      portForm: { allPorts: false, port: "", portEnd: "", transport: "tcp", protocol: "", label: "" },
      portError: null,
      // Port-group apply (separate inline form, one resource at a time)
      portGroups: [],
      groupFormFor: null,
      selectedGroupId: "",
      groupError: null,
      groupApplyInfo: null,  // small feedback line after a successful apply
    };
  },
  async mounted() {
    await Promise.all([this.loadSite(), this.loadResources(), this.loadPortGroups()]);
  },
  watch: {
    siteId: {
      async handler() {
        await Promise.all([this.loadSite(), this.loadResources()]);
      },
    },
  },
  computed: {
    _lang() { return locale.current; },
    typeLabels() {
      void this.lang;
      return {
        computer: t("resources.type_computer"),
        router: t("resources.type_router"),
        printer: t("resources.type_printer"),
        nas: t("resources.type_nas"),
        camera: t("resources.type_camera"),
        iot: t("resources.type_iot"),
        "virt-host": t("resources.type_virt"),
        management: t("resources.type_mgmt"),
        other: t("resources.type_other"),
      };
    },
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    async loadSite() {
      try {
        const res = await fetch("/api/v1/sites/" + this.siteId);
        if (res.ok) this.site = await res.json();
      } catch {}
    },
    async loadPortGroups() {
      try {
        const res = await fetch("/api/v1/port-groups");
        if (res.ok) this.portGroups = await res.json();
      } catch {}
    },
    openGroupForm(resourceId) {
      this.groupFormFor = resourceId;
      this.selectedGroupId = this.portGroups.length > 0 ? this.portGroups[0].id : "";
      this.groupError = null;
      this.groupApplyInfo = null;
      // Close the manual port form if open so the two UIs don't stack.
      this.portFormFor = null;
    },
    closeGroupForm() {
      this.groupFormFor = null;
      this.groupError = null;
      this.groupApplyInfo = null;
    },
    selectedGroupMembers() {
      const g = this.portGroups.find((x) => x.id === this.selectedGroupId);
      return g ? g.members : [];
    },
    async applyGroup() {
      if (!this.groupFormFor || !this.selectedGroupId) return;
      this.groupError = null;
      this.groupApplyInfo = null;
      try {
        const res = await fetch("/api/v1/resources/" + this.groupFormFor + "/ports/apply-group", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ portGroupId: this.selectedGroupId }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        const result = await res.json();
        await this.loadResources();
        if (result.added === 0 && result.skippedExisting > 0) {
          this.groupApplyInfo = "Alle Ports der Gruppe sind bereits auf dieser Ressource. Nichts zu tun.";
        } else {
          this.groupApplyInfo = result.added + " Port(s) hinzugefügt"
              + (result.skippedExisting > 0
                  ? ", " + result.skippedExisting + " war(en) schon vorhanden."
                  : ".");
        }
      } catch (e) {
        this.groupError = t("resources.error_save", { error: e.message });
      }
    },
    async loadResources() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/sites/" + this.siteId + "/resources");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.resources = await res.json();
      } catch (e) {
        this.error = t("resources.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    openCreate() {
      this.modal = "create";
      this.editId = null;
      this.form = { name: "", ip: "", description: "" };
      this.formError = null;
    },
    openEdit(r) {
      this.modal = "edit";
      this.editId = r.id;
      this.form = { name: r.name, ip: r.ip, description: r.description || "", type: r.type || "computer" };
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
        const url = this.editId
            ? "/api/v1/resources/" + this.editId
            : "/api/v1/sites/" + this.siteId + "/resources";
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
        await this.loadResources();
        this.closeModal();
      } catch (e) {
        this.formError = t("resources.error_save", { error: e.message });
      } finally {
        this.submitting = false;
      }
    },
    async deleteResource(r) {
      if (!confirm(t("resources.confirm_delete", { name: r.name }))) return;
      try {
        const res = await fetch("/api/v1/resources/" + r.id, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.loadResources();
      } catch (e) {
        this.error = t("resources.error_delete", { error: e.message });
      }
    },
    openPortForm(resourceId) {
      this.portFormFor = resourceId;
      this.portForm = { allPorts: false, port: "", portEnd: "", transport: "tcp", protocol: "", label: "" };
      this.portError = null;
      // Close the group-apply UI to keep only one inline form open at a time.
      this.groupFormFor = null;
    },
    closePortForm() {
      this.portFormFor = null;
      this.portError = null;
    },
    async submitPort() {
      this.portError = null;
      try {
        let portNum, portEnd;
        if (this.portForm.allPorts) {
          portNum = 0;
          portEnd = null;
        } else {
          portNum = parseInt(this.portForm.port, 10);
          if (isNaN(portNum) || portNum < 1 || portNum > 65535) {
            this.portError = "Port muss zwischen 1 und 65535 liegen.";
            return;
          }
          portEnd = this.portForm.portEnd ? parseInt(this.portForm.portEnd, 10) : null;
          if (portEnd !== null && (isNaN(portEnd) || portEnd <= portNum || portEnd > 65535)) {
            this.portError = "Bereichsende muss größer als Startport und ≤ 65535 sein.";
            return;
          }
        }
        const res = await fetch("/api/v1/resources/" + this.portFormFor + "/ports", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            port: portNum,
            portEnd,
            transport: this.portForm.transport,
            protocol: this.portForm.protocol,
            label: this.portForm.label || null,
          }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        await this.loadResources();
        this.closePortForm();
      } catch (e) {
        this.portError = t("resources.error_port_del", { error: e.message });
      }
    },
    async deletePort(resourceId, port) {
      if (!confirm(t("resources.confirm_port"))) return;
      try {
        const res = await fetch("/api/v1/resources/" + resourceId + "/ports/" + port.id, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.loadResources();
      } catch (e) {
        this.error = t("resources.error_port_del", { error: e.message });
      }
    },
    backToSites() {
      this.$router.push({ name: "sites" });
    },
    typeLabel(type) {
      return this.typeLabels[type] || type;
    },
  },
  template: `
    <div class="page-header">
      <div style="display: flex; align-items: center; gap: var(--space-3)">
        <button class="btn btn-ghost btn-sm" @click="backToSites">← Standorte</button>
        <div style="display: flex; flex-direction: column; gap: 2px">
          <h1 style="margin: 0; font-size: var(--text-xl); font-weight: 600; letter-spacing: -0.02em">{{ t('resources.title') }}</h1>
          <div v-if="site" style="display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-xs); color: var(--fg3)">
            <span>{{ site.name }}</span>
            <span style="color: var(--border-strong)">·</span>
            <span class="mono">{{ site.cidr }}</span>
            <span style="color: var(--border-strong)">·</span>
            <span>{{ resources.length }} Host{{ resources.length !== 1 ? 's' : '' }}</span>
          </div>
        </div>
      </div>
      <button class="btn btn-primary btn-sm" @click="openCreate">{{ t('resources.create_btn') }}</button>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="resources.length === 0" class="empty-state">
      <h2>{{ t('resources.empty_title') }}</h2>
      <p>{{ t('resources.empty_desc') }}</p>
    </div>

    <div v-else class="res-grid">
      <div v-for="r in resources" :key="r.id" class="res-card">

        <!-- Card head: icon + identity + actions -->
        <div class="res-card-head">
          <div class="res-type-tile">
            <Icon :name="r.type || 'computer'" :size="22" />
          </div>
          <div class="res-identity">
            <div class="res-name">{{ r.name }}</div>
            <div class="mono" style="font-size: var(--text-xs); color: var(--fg3)">{{ r.ip }}</div>
          </div>
          <div class="res-actions">
            <button class="btn btn-ghost btn-sm" @click="openEdit(r)"><Icon name="edit" :size="13" />{{ t('resources.btn_edit') }}</button>
            <button class="btn btn-ghost btn-sm" @click="deleteResource(r)"><Icon name="trash" :size="13" />{{ t('resources.btn_delete') }}</button>
          </div>
        </div>

        <!-- Description if present -->
        <div v-if="r.description" class="res-desc">{{ r.description }}</div>

        <!-- Ports section -->
        <div class="res-ports-section">
          <div class="res-ports-header">
            <span style="font-size: var(--text-xs); font-weight: 600; color: var(--fg3); text-transform: uppercase; letter-spacing: 0.08em">Ports</span>
            <div style="display: flex; gap: var(--space-2)">
              <button class="btn btn-ghost btn-sm" @click="portFormFor === r.id ? closePortForm() : openPortForm(r.id)">
                {{ portFormFor === r.id ? '✕ Abbrechen' : t('resources.btn_add_port') }}
              </button>
              <button class="btn btn-ghost btn-sm"
                      :disabled="portGroups.length === 0"
                      :title="portGroups.length === 0 ? 'Lege zuerst eine Port-Gruppe an' : ''"
                      @click="groupFormFor === r.id ? closeGroupForm() : openGroupForm(r.id)">
                {{ groupFormFor === r.id ? '✕ ' + t('common.cancel') : '+ Aus Gruppe' }}
              </button>
            </div>
          </div>

          <!-- Port chips -->
          <div class="res-port-chips" v-if="r.ports.length > 0 || portFormFor !== r.id">
            <span v-if="r.ports.length === 0 && portFormFor !== r.id"
                  style="font-size: var(--text-xs); color: var(--fg3); font-family: var(--font-sans)">
              {{ t('resources.no_ports') }}
            </span>
            <span v-for="p in r.ports" :key="p.id" class="res-port-chip">
              <span class="mono" style="font-size: var(--text-xs)">{{ p.port === 0 ? 'alle' : (p.portEnd ? p.port + '–' + p.portEnd : p.port) }}/{{ p.transport }}</span>
              <span style="color: var(--fg2); font-size: var(--text-xs)">{{ p.protocol }}</span>
              <button class="res-port-remove" @click="deletePort(r.id, p)" title="Port entfernen">✕</button>
            </span>
          </div>

          <!-- Add port form -->
          <div v-if="portFormFor === r.id" class="res-inline-form">
            <form @submit.prevent="submitPort">
              <div class="res-form-row">
                <div class="field" style="margin: 0">
                  <label style="display: flex; align-items: center; gap: var(--space-2)">
                    <input type="checkbox" v-model="portForm.allPorts" style="width: auto; margin: 0" />
                    Alle Ports
                  </label>
                  <input v-if="!portForm.allPorts" class="input mono" type="number" min="1" max="65535" v-model="portForm.port" :required="!portForm.allPorts" placeholder="22" style="width: 100px; margin-top: var(--space-1)" />
                  <span v-else class="mono" style="display: inline-block; padding: 6px 10px; background: var(--surface2); border-radius: var(--radius-sm); font-size: var(--text-xs); margin-top: var(--space-1)">alle</span>
                </div>
                <div v-if="!portForm.allPorts" class="field" style="margin: 0">
                  <label>bis Port (opt.)</label>
                  <input class="input mono" type="number" min="2" max="65535" v-model="portForm.portEnd" placeholder="–" style="width: 90px" />
                </div>
                <div class="field" style="margin: 0">
                  <label>Transport</label>
                  <select class="select" v-model="portForm.transport" style="width: 90px">
                    <option value="tcp">tcp</option>
                    <option value="udp">udp</option>
                    <option value="both">both</option>
                  </select>
                </div>
                <div class="field" style="margin: 0">
                  <label>Protokoll</label>
                  <select class="select" v-model="portForm.protocol" required style="width: 130px">
                    <option value="">—</option>
                    <option>RDP</option>
                    <option>VNC</option>
                    <option>SSH</option>
                    <option>SFTP</option>
                    <option>HTTP</option>
                    <option>HTTPS</option>
                    <option>SMB</option>
                    <option>X11</option>
                    <option>CUSTOM</option>
                  </select>
                </div>
                <div class="field" style="margin: 0; flex: 1">
                  <label>Label (optional)</label>
                  <input class="input" v-model="portForm.label" placeholder="z.B. Admin RDP" />
                </div>
                <div style="display: flex; gap: var(--space-2); align-self: flex-end">
                  <button type="submit" class="btn btn-primary btn-sm">Hinzufügen</button>
                </div>
              </div>
              <div v-if="portError" class="error-banner" style="margin-top: var(--space-3)">{{ portError }}</div>
            </form>
          </div>

          <!-- Group apply form -->
          <div v-if="groupFormFor === r.id" class="res-inline-form">
            <form @submit.prevent="applyGroup">
              <div class="res-form-row">
                <div class="field" style="margin: 0; min-width: 220px">
                  <label>Port-Gruppe</label>
                  <select class="select" v-model="selectedGroupId" required>
                    <option v-for="g in portGroups" :key="g.id" :value="g.id">{{ g.name }}</option>
                  </select>
                </div>
                <div style="flex: 1; align-self: flex-end; font-size: var(--text-xs); color: var(--fg3); font-family: var(--font-sans); padding-bottom: 8px">
                  <span v-if="selectedGroupMembers().length === 0">Gruppe enthält keine Ports</span>
                  <span v-else>
                    Fügt hinzu:
                    <span v-for="(m, i) in selectedGroupMembers()" :key="m.id">
                      <span class="mono">{{ m.port === 0 ? 'alle' : (m.portEnd ? m.port + '–' + m.portEnd : m.port) }}/{{ m.transport }}</span>{{ i < selectedGroupMembers().length - 1 ? ', ' : '' }}
                    </span>
                  </span>
                </div>
                <div style="align-self: flex-end">
                  <button type="submit" class="btn btn-primary btn-sm" :disabled="!selectedGroupId || selectedGroupMembers().length === 0">Anwenden</button>
                </div>
              </div>
              <div v-if="groupApplyInfo" class="callout callout-info" style="margin-top: var(--space-3)"><div>{{ groupApplyInfo }}</div></div>
              <div v-if="groupError" class="error-banner" style="margin-top: var(--space-3)">{{ groupError }}</div>
            </form>
          </div>
        </div>
      </div>
    </div>

    <!-- Create / Edit modal -->
    <div v-if="modal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal modal-lg">
        <div class="modal-header">
          <h2>{{ modal === 'create' ? t('resources.modal_create') : t('resources.modal_edit') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
        </div>
        <form @submit.prevent="submit">
          <div class="modal-body">
            <div v-if="formError" class="error-banner" style="margin-bottom: var(--space-4)">{{ formError }}</div>

            <!-- Type selector as visual grid -->
            <div class="field" style="margin-bottom: var(--space-5)">
              <label>{{ t('resources.field_type') }}</label>
              <div class="res-type-grid">
                <label v-for="opt in [
                  {v:'computer',   l: t('resources.type_computer')},
                  {v:'router',     l: t('resources.type_router')},
                  {v:'printer',    l: t('resources.type_printer')},
                  {v:'nas',        l: t('resources.type_nas')},
                  {v:'camera',     l: t('resources.type_camera')},
                  {v:'iot',        l: t('resources.type_iot')},
                  {v:'virt-host',  l: t('resources.type_virt')},
                  {v:'management', l: t('resources.type_mgmt')},
                  {v:'other',      l: t('resources.type_other')},
                ]" :key="opt.v" class="res-type-option" :class="{ active: form.type === opt.v }">
                  <input type="radio" :value="opt.v" v-model="form.type" style="position:absolute;opacity:0;pointer-events:none" />
                  <Icon :name="opt.v" :size="20" />
                  <span>{{ opt.l }}</span>
                </label>
              </div>
            </div>

            <div style="display: grid; grid-template-columns: 1fr 180px; gap: var(--space-4); margin-bottom: var(--space-4)">
              <div class="field" style="margin: 0">
                <label for="resName">{{ t('resources.field_name') }}</label>
                <input id="resName" class="input" v-model="form.name" required :placeholder="t('resources.field_name_ph')" />
              </div>
              <div class="field" style="margin: 0">
                <label for="resIp">{{ t('resources.field_ip') }}</label>
                <input id="resIp" class="input mono" v-model="form.ip" required :placeholder="t('resources.field_ip_ph')" />
              </div>
            </div>

            <div class="field" style="margin: 0">
              <label for="resDesc">{{ t('resources.field_desc') }} <span style="color:var(--fg3); font-weight:400">(optional)</span></label>
              <textarea id="resDesc" class="textarea" rows="2" v-model="form.description" :placeholder="t('resources.field_desc_ph')"></textarea>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="closeModal">{{ t('common.cancel') }}</button>
            <button type="submit" class="btn btn-primary" :disabled="submitting">
              {{ submitting ? t('resources.btn_saving') : t('resources.btn_save') }}
            </button>
          </div>
        </form>
      </div>
    </div>
  `,
});
