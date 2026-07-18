import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";

// Tabelle ueber alle Resourcen quer ueber alle Sites. Liefert den schnellen
// Drill-Down ("wo ist Drucker XY"); fuer das Bearbeiten leitet der Klick auf
// die Site-Detailseite weiter, weil dort die Port-Verwaltung sitzt.
//
// Keine Bulk-Editierung hier, kein Inline-Edit. Die View ist bewusst read-only;
// alle Mutationen passieren in /networks/:siteId/resources.

export default defineComponent({
  name: "AllResourcesView",
  components: { Icon },
  data() {
    return {
      lang: locale.current,
      loading: true,
      error: null,
      sites: [],
      resources: [],
      filter: "",
      typeFilter: "",
    };
  },
  async mounted() {
    await this.load();
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
    sitesById() {
      const m = {};
      for (const s of this.sites) m[s.id] = s;
      return m;
    },
    filtered() {
      const q = this.filter.trim().toLowerCase();
      return this.resources.filter((r) => {
        if (this.typeFilter && r.type !== this.typeFilter) return false;
        if (!q) return true;
        return (
          r.name.toLowerCase().includes(q) ||
          r.ip.includes(q) ||
          (this.sitesById[r.siteId]?.name || "").toLowerCase().includes(q)
        );
      });
    },
    types() {
      return Object.keys(this.typeLabels);
    },
  },
  methods: {
    t(key, vars) { return t(key, vars); },
    async load() {
      this.loading = true;
      this.error = null;
      try {
        const sitesRes = await fetch("/api/v1/sites");
        if (!sitesRes.ok) throw new Error(t("allres.err_load_sites"));
        this.sites = await sitesRes.json();

        // Resourcen werden je Site geliefert — hier zusammenflicken.
        const lists = await Promise.all(
          this.sites.map(async (s) => {
            const r = await fetch("/api/v1/sites/" + s.id + "/resources");
            if (!r.ok) return [];
            return await r.json();
          })
        );
        this.resources = lists.flat();
      } catch (e) {
        this.error = t("allres.error_load", { error: e.message });
      } finally {
        this.loading = false;
      }
    },
    typeLabel(type) {
      return this.typeLabels[type] || type;
    },
    portList(r) {
      if (!r.ports || r.ports.length === 0) return "—";
      return r.ports
        .slice()
        .sort((a, b) => a.transport.localeCompare(b.transport) || a.port - b.port)
        .map((p) => {
          const proto = !p.protocol || p.protocol === "CUSTOM" ? p.transport.toUpperCase() : p.protocol;
          return proto + " " + p.port;
        })
        .join(", ");
    },
  },
  template: `
    <div>
      <div class="page-header">
        <div>
          <h1 style="margin: 0 0 2px; font-size: var(--text-xl); font-weight: 600; letter-spacing: -0.02em">{{ t('allres.title') }}</h1>
          <div style="font-size: var(--text-xs); color: var(--fg3)">
            {{ filtered.length === 1 ? t('allres.results', { n: filtered.length }) : t('allres.results_p', { n: filtered.length }) }}
          </div>
        </div>
      </div>

      <!-- Filter bar -->
      <div style="display: flex; gap: var(--space-3); margin-bottom: var(--space-5); align-items: center">
        <input v-model="filter" type="search" :placeholder="t('allres.filter_ph')" class="input" style="max-width: 320px" />
        <select v-model="typeFilter" class="select" style="width: 160px">
          <option value="">{{ t('allres.all_types') }}</option>
          <option v-for="type in types" :key="type" :value="type">{{ typeLabel(type) }}</option>
        </select>
        <span v-if="filter || typeFilter" style="font-size: var(--text-xs); color: var(--fg3)">
          {{ filtered.length === 1 ? t('allres.results', { n: filtered.length }) : t('allres.results_p', { n: filtered.length }) }}
        </span>
      </div>

      <div v-if="loading" class="muted">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="error-banner">{{ error }}</div>
      <div v-else-if="filtered.length === 0" class="empty-state">
        <h2>{{ t('allres.empty_title') }}</h2>
        <p>{{ t('allres.empty_desc') }}</p>
      </div>

      <!-- Grouped by site -->
      <div v-else class="allres-groups">
        <template v-for="site in sites" :key="site.id">
          <div v-if="filtered.some(r => r.siteId === site.id)" class="allres-group">

            <!-- Site header -->
            <div class="allres-group-head">
              <div style="display: flex; align-items: center; gap: var(--space-3)">
                <Icon name="networks" :size="14" style="color: var(--fg3); flex-shrink: 0" />
                <span style="font-size: var(--text-xs); font-weight: 600; color: var(--fg2); text-transform: uppercase; letter-spacing: 0.08em">{{ site.name }}</span>
                <span class="mono" style="font-size: var(--text-xs); color: var(--fg3)">{{ site.cidr }}</span>
              </div>
              <router-link :to="{ name: 'resources', params: { siteId: site.id } }" class="btn btn-ghost btn-sm">
                {{ t('allres.edit_btn') }}
              </router-link>
            </div>

            <!-- Resource rows for this site -->
            <div class="allres-rows">
              <div v-for="r in filtered.filter(x => x.siteId === site.id)" :key="r.id" class="allres-row">
                <div class="allres-icon-col">
                  <div class="allres-type-dot">
                    <Icon :name="r.type || 'computer'" :size="16" />
                  </div>
                </div>
                <div class="allres-main">
                  <span class="allres-name">{{ r.name }}</span>
                  <span class="allres-type-label">{{ typeLabel(r.type || 'computer') }}</span>
                </div>
                <div class="allres-ip mono">{{ r.ip }}</div>
                <div class="allres-ports">
                  <span v-if="!r.ports || r.ports.length === 0" style="color: var(--fg3); font-size: var(--text-xs)">—</span>
                  <span v-else class="mono" style="font-size: var(--text-xs); color: var(--fg2)">{{ portList(r) }}</span>
                </div>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>
  `,
});
