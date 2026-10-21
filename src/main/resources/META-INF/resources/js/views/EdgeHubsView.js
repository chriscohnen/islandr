import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t } from "/js/i18n.js";
import { onEscape } from "/js/keyboard.js";
import { confirmDialog } from "/js/confirmDialog.js";

// Edge hubs = a second WireGuard hub at a remote site (edge-hub-entity,
// loop/TASK.md, ADR-I-0002). The central hub has no row here and keeps
// living in Settings — this page only manages the additional ones. A site
// (Networks page) can be assigned to one of these; unassigned = served by
// the central hub, same as before this feature existed.
export default defineComponent({
  name: "EdgeHubsView",
  components: { Icon },
  data() {
    return {
      edgeHubs: [],
      loading: true,
      error: null,
      modal: null, // null | "create" | "edit"
      form: { name: "", enabled: true, lat: "", lng: "", locationLabel: "", endpoint: "", serverPublicKey: "", dnsServerIp: "" },
      editId: null,
      submitting: false,
      formError: null,
    };
  },
  async mounted() {
    await this.load();
    this._offEscape = onEscape(() => { if (this.modal) this.closeModal(); });
  },
  beforeUnmount() {
    if (this._offEscape) this._offEscape();
  },
  methods: {
    t,
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const res = await fetch("/api/v1/edge-hubs");
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.edgeHubs = await res.json();
      } catch (e) {
        this.error = t("edge_hubs.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    openCreate() {
      this.modal = "create";
      this.editId = null;
      this.form = { name: "", enabled: true, lat: "", lng: "", locationLabel: "", endpoint: "", serverPublicKey: "", dnsServerIp: "" };
      this.formError = null;
    },
    openEdit(hub) {
      this.modal = "edit";
      this.editId = hub.id;
      this.form = {
        name: hub.name,
        enabled: hub.enabled,
        lat: hub.lat,
        lng: hub.lng,
        locationLabel: hub.locationLabel || "",
        endpoint: hub.endpoint || "",
        serverPublicKey: hub.serverPublicKey || "",
        dnsServerIp: hub.dnsServerIp || "",
      };
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
        const url = this.editId ? "/api/v1/edge-hubs/" + this.editId : "/api/v1/edge-hubs";
        const method = this.editId ? "PUT" : "POST";
        const res = await fetch(url, {
          method,
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            name: this.form.name,
            enabled: this.form.enabled,
            lat: this.form.lat === "" ? null : Number(this.form.lat),
            lng: this.form.lng === "" ? null : Number(this.form.lng),
            locationLabel: this.form.locationLabel || null,
            endpoint: this.form.endpoint || null,
            serverPublicKey: this.form.serverPublicKey || null,
            dnsServerIp: this.form.dnsServerIp || null,
          }),
        });
        if (!res.ok) {
          const body = await res.text();
          throw new Error("HTTP " + res.status + (body ? " — " + body.slice(0, 200) : ""));
        }
        await this.load();
        this.closeModal();
      } catch (e) {
        this.formError = t("edge_hubs.error_save", { error: e.message });
      } finally {
        this.submitting = false;
      }
    },
    async deleteEdgeHub(hub) {
      this.error = null;
      const warning = hub.siteCount > 0
        ? t("edge_hubs.confirm_del_with_sites", { name: hub.name, n: hub.siteCount })
        : t("edge_hubs.confirm_del", { name: hub.name });
      if (!await confirmDialog(warning)) return;
      try {
        const res = await fetch("/api/v1/edge-hubs/" + hub.id, { method: "DELETE" });
        if (!res.ok) throw new Error("HTTP " + res.status);
        await this.load();
      } catch (e) {
        this.error = t("edge_hubs.error_delete", { error: e.message });
      }
    },
  },
  template: `
    <div class="page-header">
      <h1>{{ t('edge_hubs.title') }} <span v-if="edgeHubs.length" class="muted" style="font-family: var(--font-mono); font-size: var(--text-md); margin-left: var(--space-3)">{{ edgeHubs.length }}</span></h1>
      <button class="btn btn-primary btn-sm" @click="openCreate">{{ t('edge_hubs.create_btn') }}</button>
    </div>

    <p class="muted" style="margin-bottom: var(--space-4); font-size: var(--text-sm)">{{ t('edge_hubs.hint') }}</p>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="edgeHubs.length === 0" class="empty-state">
      <h2>{{ t('edge_hubs.empty_title') }}</h2>
      <p>{{ t('edge_hubs.empty_desc') }}</p>
    </div>

    <table v-else class="table">
      <thead>
        <tr>
          <th>{{ t('edge_hubs.th_name') }}</th>
          <th>{{ t('edge_hubs.th_status') }}</th>
          <th>{{ t('edge_hubs.th_coords') }}</th>
          <th>{{ t('edge_hubs.th_sites') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="h in edgeHubs" :key="h.id">
          <td>
            {{ h.name }}
            <div v-if="h.locationLabel" class="muted" style="font-size: var(--text-xs)">{{ h.locationLabel }}</div>
            <!-- Says why this edge cannot take peers yet. Without it the
                 refusal in the peer dialog names a condition the admin would
                 have to go looking for. -->
            <span v-if="!h.canServePeers" class="badge badge-warning" style="margin-top:4px" :title="t('edge_hubs.incomplete_hint')">{{ t('edge_hubs.incomplete') }}</span>
          </td>
          <td>
            <span style="display:inline-flex;align-items:center;gap:var(--space-2)">
              <span :style="h.enabled ? 'color:var(--status-ok)' : 'color:var(--fg3)'" style="font-size:10px">{{ h.enabled ? '●' : '○' }}</span>
              {{ h.enabled ? t('edge_hubs.status_enabled') : t('edge_hubs.status_disabled') }}
            </span>
          </td>
          <td class="mono">{{ h.lat }}, {{ h.lng }}</td>
          <td>{{ h.siteCount }}</td>
          <td style="text-align: right">
            <button class="btn btn-ghost btn-sm" @click="openEdit(h)"><Icon name="edit" :size="13" />{{ t('edge_hubs.btn_edit') }}</button>
            <button class="btn btn-ghost btn-sm" @click="deleteEdgeHub(h)"><Icon name="trash" :size="13" />{{ t('edge_hubs.btn_delete') }}</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="modal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ modal === 'create' ? t('edge_hubs.modal_create') : t('edge_hubs.modal_edit') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeModal">✕</button>
        </div>
        <form @submit.prevent="submit">
          <div class="modal-body">
            <div v-if="formError" class="error-banner">{{ formError }}</div>
            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="edgeHubName">{{ t('edge_hubs.field_name') }}</label>
              <input id="edgeHubName" class="input" v-model="form.name" required :placeholder="t('edge_hubs.field_name_ph')" />
            </div>
            <div class="field" style="margin-bottom: var(--space-4); display:flex; align-items:center; gap: var(--space-2)">
              <input id="edgeHubEnabled" type="checkbox" v-model="form.enabled" style="width:15px;height:15px;accent-color:var(--accent);margin:0" />
              <label for="edgeHubEnabled" style="margin:0">{{ t('edge_hubs.field_enabled') }}</label>
            </div>
            <div style="display:flex; gap: var(--space-4); margin-bottom: var(--space-4)">
              <div class="field" style="flex:1">
                <label for="edgeHubLat">{{ t('edge_hubs.field_lat') }}</label>
                <input id="edgeHubLat" class="input mono" type="number" step="any" min="-90" max="90"
                       v-model="form.lat" required placeholder="50.1109" />
              </div>
              <div class="field" style="flex:1">
                <label for="edgeHubLng">{{ t('edge_hubs.field_lng') }}</label>
                <input id="edgeHubLng" class="input mono" type="number" step="any" min="-180" max="180"
                       v-model="form.lng" required placeholder="8.6821" />
              </div>
            </div>
            <div class="field">
              <label for="edgeHubLocationLabel">{{ t('edge_hubs.field_location_label') }} <span style="color:var(--fg3); font-weight:400">(optional)</span></label>
              <input id="edgeHubLocationLabel" class="input" v-model="form.locationLabel" :placeholder="t('edge_hubs.field_location_label_ph')" />
            </div>

            <div class="field">
              <label for="edgeHubEndpoint">{{ t('edge_hubs.field_endpoint') }}</label>
              <input id="edgeHubEndpoint" class="input mono" v-model="form.endpoint" placeholder="edge.example.com:51820" />
              <div class="field-hint">{{ t('edge_hubs.field_endpoint_hint') }}</div>
            </div>

            <div class="field">
              <label for="edgeHubKey">{{ t('edge_hubs.field_server_key') }}</label>
              <input id="edgeHubKey" class="input mono" v-model="form.serverPublicKey" placeholder="xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx=" />
              <div class="field-hint">{{ t('edge_hubs.field_server_key_hint') }}</div>
            </div>

            <div class="field">
              <label for="edgeHubDns">{{ t('edge_hubs.field_dns') }} <span style="color:var(--fg3); font-weight:400">(optional)</span></label>
              <input id="edgeHubDns" class="input mono" v-model="form.dnsServerIp" placeholder="10.60.0.1" />
              <div class="field-hint">{{ t('edge_hubs.field_dns_hint') }}</div>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-ghost" @click="closeModal">{{ t('common.cancel') }}</button>
            <button type="submit" class="btn btn-primary" :disabled="submitting">
              {{ submitting ? t('edge_hubs.btn_saving') : t('edge_hubs.btn_save') }}
            </button>
          </div>
        </form>
      </div>
    </div>
  `,
});
