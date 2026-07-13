import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";

// Sites = organisational grouping for resources. The CIDR is informational,
// rendered next to the name; it does NOT participate in nftables rules.
// Resources live under each site and are managed via ResourcesView.
export default defineComponent({
  name: "SitesView",
  components: { Icon },
  data() {
    return {
      sites: [],
      peers: [],   // site-type peers available as gateway candidates
      loading: true,
      error: null,
      modal: null,        // null | "create" | "edit"
      form: { name: "", cidr: "", description: "", lat: "", lng: "", gatewayPeerId: "" },
      editId: null,
      submitting: false,
      formError: null,
      lang: locale.current,
    };
  },
  async mounted() {
    await this.load();
  },
  computed: {
    _lang() { return locale.current; },
    // Routed CIDRs of the selected gateway peer, offered as one-click fills for
    // the site CIDR so it need not be retyped.
    gatewayRanges() {
      const p = this.peers.find((x) => x.id === this.form.gatewayPeerId);
      if (!p || !p.siteAllowedCidrs) return [];
      return p.siteAllowedCidrs.split(",").map((s) => s.trim()).filter(Boolean);
    },
  },
  methods: {
    useRange(cidr) { this.form.cidr = cidr; },

    t(key, vars) { return t(key, vars); },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const [sitesRes, peersRes] = await Promise.all([
          fetch("/api/v1/sites"),
          fetch("/api/v1/peers"),
        ]);
        if (!sitesRes.ok) throw new Error("HTTP " + sitesRes.status);
        this.sites = await sitesRes.json();
        if (peersRes.ok) {
          const all = await peersRes.json();
          // only site-type peers make sense as gateways
          this.peers = all.filter(p => p.type === "site");
        }
      } catch (e) {
        this.error = t("sites.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    openCreate() {
      this.modal = "create";
      this.editId = null;
      this.form = { name: "", cidr: "", description: "", lat: "", lng: "", gatewayPeerId: "" };
      this.formError = null;
    },
    openEdit(site) {
      this.modal = "edit";
      this.editId = site.id;
      this.form = {
        name: site.name,
        cidr: site.cidr,
        description: site.description || "",
        lat: site.lat ?? "",
        lng: site.lng ?? "",
        gatewayPeerId: site.gatewayPeerId || "",
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
        const url = this.editId ? "/api/v1/sites/" + this.editId : "/api/v1/sites";
        const method = this.editId ? "PUT" : "POST";
        const res = await fetch(url, {
          method,
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            ...this.form,
            lat: this.form.lat !== "" ? parseFloat(this.form.lat) : null,
            lng: this.form.lng !== "" ? parseFloat(this.form.lng) : null,
            gatewayPeerId: this.form.gatewayPeerId || null,
          }),
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

    pasteCoordinates(event) {
      const text = (event.clipboardData || window.clipboardData).getData("text").trim();
      const parts = text.split(/[,;\s]+/).map(s => s.trim()).filter(Boolean);
      if (parts.length >= 2) {
        const lat = parseFloat(parts[0]);
        const lng = parseFloat(parts[1]);
        if (!isNaN(lat) && !isNaN(lng)) {
          this.form.lat = lat;
          this.form.lng = lng;
          return;
        }
      }
      // Fallback: paste only into lat field
      this.form.lat = text;
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
          <th>{{ t('sites.th_gateway') }}</th>
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
          <td>
            <span v-if="!s.gatewayPeerId" class="muted">—</span>
            <span v-else style="display:inline-flex;align-items:center;gap:var(--space-2)">
              <span :style="s.gatewayOnline ? 'color:var(--status-ok)' : 'color:var(--fg3)'"
                    style="font-size:10px">{{ s.gatewayOnline ? '●' : '○' }}</span>
              <span>{{ s.gatewayPeerName || s.gatewayPeerId }}</span>
            </span>
          </td>
          <td class="muted">{{ s.description || "—" }}</td>
          <td>
            <button class="btn btn-ghost btn-sm" @click="goToResources(s)">
              <Icon name="resources" :size="13" />{{ s.resourceCount }}
            </button>
          </td>
          <td class="muted">{{ formatDate(s.createdAt) }}</td>
          <td style="text-align: right">
            <button class="btn btn-ghost btn-sm" @click="openEdit(s)"><Icon name="edit" :size="13" />{{ t('sites.btn_edit') }}</button>
            <button class="btn btn-ghost btn-sm" @click="deleteSite(s)"><Icon name="trash" :size="13" />{{ t('sites.btn_delete') }}</button>
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
              <input id="siteName" class="input" v-model="form.name" required
                :placeholder="t('sites.field_name_ph')" list="siteNameSuggestions" autocomplete="off" />
              <datalist id="siteNameSuggestions">
                <option value="Büro Hamburg"></option>
                <option value="Büro Berlin"></option>
                <option value="Büro München"></option>
                <option value="Niederlassung Wien"></option>
                <option value="Niederlassung Zürich"></option>
                <option value="Labor Süd"></option>
                <option value="Rechenzentrum Frankfurt"></option>
                <option value="Home Office"></option>
              </datalist>
            </div>
            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="siteCidr">{{ t('sites.field_cidr') }}</label>
              <input id="siteCidr" class="input mono" v-model="form.cidr" required placeholder="10.20.0.0/16" />
              <div class="field-hint">{{ t('sites.hint_cidr') }}</div>
            </div>
            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="siteGateway">{{ t('sites.field_gateway') }}</label>
              <select id="siteGateway" class="select" v-model="form.gatewayPeerId">
                <option value="">{{ t('sites.field_gateway_none') }}</option>
                <option v-for="p in peers" :key="p.id" :value="p.id">{{ p.name }} ({{ p.assignedIp }})</option>
              </select>
              <div v-if="peers.length === 0" class="field-hint">
                {{ t('sites.field_gateway_empty') }}
                <router-link :to="{ name: 'peers' }">{{ t('sites.field_gateway_empty_link') }}</router-link>
              </div>
              <div v-else class="field-hint">{{ t('sites.field_gateway_hint') }}</div>
              <!-- Adopt one of the gateway peer's routed CIDRs into the site CIDR
                   above, so it need not be retyped. Manual entry stays possible. -->
              <div v-if="gatewayRanges.length" style="margin-top: var(--space-2); display: flex; flex-wrap: wrap; gap: var(--space-2); align-items: center">
                <span class="muted" style="font-size: var(--text-xs)">{{ t('sites.field_gateway_ranges') }}</span>
                <button v-for="r in gatewayRanges" :key="r" type="button"
                        class="btn btn-sm mono" :class="form.cidr === r ? 'btn-primary' : 'btn-ghost'"
                        style="font-size: var(--text-xs)" @click="useRange(r)">{{ r }}</button>
              </div>
            </div>
            <div class="field" style="margin-bottom: var(--space-4)">
              <label for="siteDesc">{{ t('sites.field_desc') }}</label>
              <textarea id="siteDesc" class="textarea" rows="2" v-model="form.description" placeholder="Optional"></textarea>
            </div>
            <div class="field">
              <label>{{ t('sites.field_geo') }}</label>
              <div style="display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3)">
                <input class="input mono" v-model="form.lat" type="number" step="any" min="-90" max="90"
                  :placeholder="t('sites.field_lat') + ' (z.B. 53.5753)'"
                  @paste.prevent="pasteCoordinates($event)" />
                <input class="input mono" v-model="form.lng" type="number" step="any" min="-180" max="180"
                  :placeholder="t('sites.field_lng') + ' (z.B. 10.0153)'" />
              </div>
              <div class="field-hint">{{ t('sites.field_geo_hint') }}</div>
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
