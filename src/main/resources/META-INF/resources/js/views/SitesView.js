import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";
import { onEscape } from "/js/keyboard.js";

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
      allResources: [],  // all resources across all sites, for the type breakdown panel below the table
      loading: true,
      error: null,
      modal: null,        // null | "create" | "edit"
      form: { name: "", cidr: "", description: "", gatewayPeerId: "", subdomain: "", dnsServerIp: "" },
      editId: null,
      submitting: false,
      formError: null,
      lang: locale.current,
    };
  },
  async mounted() {
    await this.load();
    this._offEscape = onEscape(() => { if (this.modal) this.closeModal(); });
  },
  beforeUnmount() {
    if (this._offEscape) this._offEscape();
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
    // Never written into form.subdomain automatically — placeholder/accept-chip
    // only, same "suggestion, not silent" pattern as the Resource DNS-name field.
    subdomainSuggestion() {
      return this.form.subdomain ? "" : this.slugifySubdomain(this.form.name);
    },
    // Never written into form.dnsServerIp automatically — same "suggestion,
    // not silent" pattern as subdomainSuggestion. Heuristic only: most home/
    // office routers use the first host of the LAN as their own address, but
    // this is a guess, not a fact — the field stays freely editable/clearable.
    dnsServerIpSuggestion() {
      if (this.form.dnsServerIp) return "";
      const base = (this.form.cidr || "").split("/")[0].trim();
      const octets = base.split(".");
      if (octets.length !== 4 || octets.some((o) => o === "" || isNaN(Number(o)))) return "";
      return `${octets[0]}.${octets[1]}.${octets[2]}.1`;
    },
    // Aggregates all resources (across every site) by type, for the
    // breakdown panel below the table. Mirrors ResourcesView's typeLabels
    // pattern — same label keys, so the vocabulary stays consistent between
    // the Networks overview and the per-site Resources view.
    resourceTypeCounts() {
      const counts = {};
      for (const r of this.allResources) {
        const type = r.type || "computer";
        counts[type] = (counts[type] || 0) + 1;
      }
      return counts;
    },
  },
  methods: {
    useRange(cidr) { this.form.cidr = cidr; },

    t(key, vars) { return t(key, vars); },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const [sitesRes, peersRes, resourcesRes] = await Promise.all([
          fetch("/api/v1/sites"),
          fetch("/api/v1/peers"),
          fetch("/api/v1/resources"),
        ]);
        if (!sitesRes.ok) throw new Error("HTTP " + sitesRes.status);
        this.sites = await sitesRes.json();
        if (peersRes.ok) {
          const all = await peersRes.json();
          // only site-type peers make sense as gateways
          this.peers = all.filter(p => p.type === "site");
        }
        // Used only for the resource-type breakdown panel below the table;
        // the per-site RESOURCES column count comes from site.resourceCount.
        if (resourcesRes.ok) this.allResources = await resourcesRes.json();
      } catch (e) {
        this.error = t("sites.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    openCreate() {
      this.modal = "create";
      this.editId = null;
      this.form = { name: "", cidr: "", description: "", gatewayPeerId: "", subdomain: "", dnsServerIp: "" };
      this.formError = null;
    },
    openEdit(site) {
      this.modal = "edit";
      this.editId = site.id;
      this.form = {
        name: site.name,
        cidr: site.cidr,
        description: site.description || "",
        gatewayPeerId: site.gatewayPeerId || "",
        subdomain: site.subdomain || "",
        dnsServerIp: site.dnsServerIp || "",
      };
      this.formError = null;
    },
    acceptSubdomainSuggestion() {
      this.form.subdomain = this.subdomainSuggestion;
    },
    acceptDnsServerIpSuggestion() {
      this.form.dnsServerIp = this.dnsServerIpSuggestion;
    },
    // Mirrors the backend's DNS-label rule (Site.subdomain / DnsQueryHandler.slugify):
    // German umlauts transliterated (ü→ue, ö→oe, ä→ae, ß→ss), other accents
    // stripped, lowercase, non [a-z0-9] runs collapsed to a hyphen, max 63 chars.
    // A pure suggestion — server-side validation is the actual source of truth.
    slugifySubdomain(name) {
      const lower = (name || "").trim().toLowerCase()
        .replace(/ä/g, "ae").replace(/ö/g, "oe").replace(/ü/g, "ue").replace(/ß/g, "ss");
      const deaccented = lower.normalize("NFD").replace(/[\u0300-\u036f]/g, "");
      const slug = deaccented.replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
      return slug.slice(0, 63).replace(/-+$/g, "");
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
    // Icon name = resource type, same convention ResourcesView uses directly
    // on its cards (`<Icon :name="r.type || 'computer'" />`) — kept identical
    // so a given type always renders the same glyph in both views.
    resourceTypeIcon(type) {
      return type || "computer";
    },
    // Mirrors ResourcesView's `typeLabels` computed (same i18n keys), so the
    // wording for a given resource type matches between the Networks
    // overview and the per-site Resources view.
    resourceTypeLabel(type) {
      const labels = {
        computer: t("resources.type_computer"),
        router: t("resources.type_router"),
        printer: t("resources.type_printer"),
        nas: t("resources.type_nas"),
        camera: t("resources.type_camera"),
        iot: t("resources.type_iot"),
        "virt-host": t("resources.type_virt"),
        rackserver: t("resources.type_rackserver"),
        kvm: t("resources.type_kvm"),
        management: t("resources.type_mgmt"),
        other: t("resources.type_other"),
      };
      return labels[type] || type;
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
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in sites" :key="s.id">
          <td>{{ s.name }}</td>
          <td class="mono">
            {{ s.cidr }}
            <span v-if="s.outsideSplitSupernet" :title="t('sites.warn_outside_supernet')"
                  style="display:inline-flex; align-items:center; gap:4px; color:var(--status-warn); font-family:var(--font-sans); font-size:var(--text-xs); font-weight:500; margin-left:var(--space-2)">
              {{ t('sites.warn_outside_supernet_short') }}
            </span>
          </td>
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
          <td style="text-align: right">
            <button class="btn btn-ghost btn-sm" @click="openEdit(s)"><Icon name="edit" :size="13" />{{ t('sites.btn_edit') }}</button>
            <button class="btn btn-ghost btn-sm" @click="deleteSite(s)"><Icon name="trash" :size="13" />{{ t('sites.btn_delete') }}</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="sites.length > 0 && Object.keys(resourceTypeCounts).length > 0" class="card" style="margin-top: var(--space-6); padding: var(--space-5)">
      <h3 style="margin: 0 0 var(--space-4) 0; font-size: var(--text-base)">{{ t('sites.resource_breakdown_title') }}</h3>
      <div style="display: flex; flex-wrap: wrap; gap: var(--space-3)">
        <div v-for="(count, type) in resourceTypeCounts" :key="type"
             style="display: flex; align-items: center; gap: var(--space-2); padding: var(--space-2) var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-md)">
          <Icon :name="resourceTypeIcon(type)" :size="16" />
          <span>{{ resourceTypeLabel(type) }}</span>
          <span class="mono muted">{{ count }}</span>
        </div>
      </div>
    </div>

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
                <option v-for="s in t('sites.name_suggestions').split('|')" :key="s" :value="s"></option>
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
              <label for="siteSubdomain">{{ t('sites.field_subdomain') }} <span style="color:var(--fg3); font-weight:400">(optional)</span></label>
              <input id="siteSubdomain" class="input mono" v-model="form.subdomain" :placeholder="subdomainSuggestion || t('sites.field_subdomain_ph')" />
              <div v-if="subdomainSuggestion" class="field-hint" style="display:flex; align-items:center; gap: var(--space-2)">
                <span>{{ t('sites.field_subdomain_suggestion', { name: subdomainSuggestion }) }}</span>
                <button type="button" class="btn btn-ghost btn-sm" style="padding: 0 var(--space-2); height: auto; min-height: 0; line-height: 1.6"
                        @click="acceptSubdomainSuggestion">{{ t('sites.field_subdomain_accept') }}</button>
              </div>
              <div class="field-hint">{{ t('sites.field_subdomain_hint') }}</div>
            </div>
            <div class="field" style="margin-top: var(--space-4)">
              <label for="siteDnsServer">{{ t('sites.field_dns_server') }} <span style="color:var(--fg3); font-weight:400">(optional)</span></label>
              <input id="siteDnsServer" class="input mono" v-model="form.dnsServerIp" :placeholder="dnsServerIpSuggestion || t('sites.field_dns_server_ph')" />
              <div v-if="dnsServerIpSuggestion" class="field-hint" style="display:flex; align-items:center; gap: var(--space-2)">
                <span>{{ t('sites.field_dns_server_suggestion', { ip: dnsServerIpSuggestion }) }}</span>
                <button type="button" class="btn btn-ghost btn-sm" style="padding: 0 var(--space-2); height: auto; min-height: 0; line-height: 1.6"
                        @click="acceptDnsServerIpSuggestion">{{ t('sites.field_subdomain_accept') }}</button>
              </div>
              <div class="field-hint">{{ t('sites.field_dns_server_hint') }}</div>
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
