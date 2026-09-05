import { defineComponent } from "vue";
import { Icon } from "/js/Icons.js";
import { t, locale } from "/js/i18n.js";
import { onEscape, onSlashFocus } from "/js/keyboard.js";

// Resources of a single site. The site is passed via route param :siteId.
// Each resource has a list of ports (port + transport + protocol-label).
// Ports are managed inline (add/remove inside the row), not via a separate modal.
export default defineComponent({
  name: "ResourcesView",
  components: { Icon },
  props: {
    siteId: { type: String, required: true },
    ironRdpEnabled: { type: Boolean, default: false },
  },
  data() {
    return {
      lang: locale.current,
      site: null,
      resources: [],
      loading: true,
      error: null,
      // Card grid vs. compact list with multi-select + bulk delete
      viewMode: "cards",     // 'cards' | 'list'
      quickFilter: "",       // matches against name or ip, substring, case-insensitive
      selectedIds: [],
      bulkDeleting: false,
      sortKey: "name",
      sortDir: 1,        // -1 = desc, 1 = asc
      // Create/edit resource modal
      modal: null,
      form: { name: "", ip: "", description: "", type: "computer", dnsName: "" },
      editId: null,
      submitting: false,
      formError: null,
      // Inline port form (one per resource at a time)
      portFormFor: null,
      portForm: { allPorts: false, port: "", portEnd: "", transport: "tcp", protocol: "", label: "", pathPrefix: "",
        // #72: empty string, not 0 — the port is "unlimited" until the admin
        // types a number, and 0 would mean "nobody may ever reach it".
        maxConcurrentUsers: "", maxReservationMinutes: "", autoApproveReservations: true },
      portError: null,
      // Port-group apply (separate inline form, one resource at a time)
      portGroups: [],
      groupFormFor: null,
      selectedGroupId: "",
      groupError: null,
      groupApplyInfo: null,  // small feedback line after a successful apply
      // Device discovery (ADR-0014)
      scanOpen: false,
      scanState: null,       // 'consent' | 'running' | 'done' | 'error'
      scanJobId: null,
      scanHosts: [],         // enriched with _selected / _name / _type for the review table
      scanProgress: { done: 0, total: 0 },
      scanFound: 0,          // live count of hosts found so far
      adoptPorts: true,      // adopt each host's discovered open ports on import
      scanError: null,
      scanCanForce: false,
      scanPollTimer: null,
      importing: false,
    };
  },
  async mounted() {
    await Promise.all([this.loadSite(), this.loadResources(), this.loadPortGroups()]);
    this._offEscape = onEscape(() => {
      if (this.scanOpen) this.closeScan();
      else if (this.modal) this.closeModal();
    });
    this._offSlash = onSlashFocus(() => this.$refs.searchInput);
  },
  unmounted() {
    // Navigating away mid-scan must not leave a poll loop running or a scan
    // orphaned on the hub — closeScan clears the timer and cancels the job.
    if (this.scanOpen) this.closeScan();
    if (this._offEscape) this._offEscape();
    if (this._offSlash) this._offSlash();
  },
  watch: {
    siteId: {
      async handler() {
        await Promise.all([this.loadSite(), this.loadResources()]);
      },
    },
    // Pre-fill the default port when a protocol is picked. Keep a hand-typed
    // custom port: only overwrite when the field is empty or still holds the
    // previous protocol's default. The user can always change it afterwards.
    "portForm.protocol"(newProto, oldProto) {
      const defaults = {
        RDP: 3389, VNC: 5900, SSH: 22, SFTP: 22, HTTP: 80, HTTPS: 443, SMB: 445, PRINT: 631, X11: 6000,
        POSTGRES: 5432, MYSQL: 3306, MARIADB: 3306, KAFKA: 9092, NATS: 4222, EMS: 7222, HOMEASSISTANT: 8123, IOBROKER: 8081,
      };
      const next = defaults[newProto];
      if (next === undefined) return; // CUSTOM / "—": leave the port as-is
      const cur = String(this.portForm.port ?? "");
      if (cur === "" || cur === String(defaults[oldProto] ?? "")) {
        this.portForm.port = next;
      }
    },
  },
  computed: {
    _lang() { return locale.current; },
    // CIDR range size is known up front, so total is stable from the first
    // poll — no indeterminate phase needed before showing real progress.
    scanProgressPct() {
      const { done, total } = this.scanProgress;
      if (!total) return 0;
      return Math.min(100, Math.round((done / total) * 100));
    },
    // Never written into form.dnsName automatically — shown as a placeholder/
    // accept-chip only, so doing nothing before Save leaves the field exactly
    // as empty as it was, instead of silently keeping a value nobody chose.
    dnsNameSuggestion() {
      return this.form.dnsName ? "" : this.slugifyDnsName(this.form.name);
    },
    filteredResources() {
      const q = this.quickFilter.trim().toLowerCase();
      if (!q) return this.resources;
      return this.resources.filter((r) =>
        (r.name || "").toLowerCase().includes(q) || (r.ip || "").toLowerCase().includes(q));
    },
    sortedResources() {
      const k = this.sortKey;
      const d = this.sortDir;
      const list = [...this.filteredResources];
      list.sort((a, b) => {
        if (k === "type") {
          const av = this.typeLabel(a.type || "computer");
          const bv = this.typeLabel(b.type || "computer");
          return d * av.localeCompare(bv);
        }
        const av = a[k] || "";
        const bv = b[k] || "";
        return d * av.localeCompare(bv, undefined, { numeric: true });
      });
      return list;
    },
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
        rackserver: t("resources.type_rackserver"),
        kvm: t("resources.type_kvm"),
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
          this.groupApplyInfo = t("resources.group_all_present");
        } else {
          this.groupApplyInfo = t("resources.group_added", { n: result.added })
              + (result.skippedExisting > 0
                  ? t("resources.group_skipped", { n: result.skippedExisting })
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
      this.form = { name: "", ip: "", description: "", type: "computer", dnsName: "", dnsFlat: false };
      this.formError = null;
    },
    openEdit(r) {
      this.modal = "edit";
      this.editId = r.id;
      // dnsName stays exactly what's on the resource — empty stays empty.
      // dnsNameSuggestion (computed) offers a suggestion without writing it
      // in; accepting it is one explicit click (acceptDnsNameSuggestion).
      this.form = {
        name: r.name, ip: r.ip, description: r.description || "", type: r.type || "computer",
        dnsName: r.dnsName || "", dnsFlat: !!r.dnsFlat,
      };
      this.formError = null;
    },
    acceptDnsNameSuggestion() {
      this.form.dnsName = this.dnsNameSuggestion;
    },
    // Mirrors the backend's DNS-label rule (Resource.dnsName / ResourceDto):
    // lowercase, non [a-z0-9] runs collapsed to a hyphen, no leading/trailing
    // hyphen, max 63 chars. A pure suggestion — the admin can still type
    // anything else (server-side validation is the actual source of truth).
    slugifyDnsName(name) {
      const slug = (name || "").trim().toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "");
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
    setView(mode) {
      this.viewMode = mode;
      if (mode === "cards") this.selectedIds = [];
    },
    isSelected(id) {
      return this.selectedIds.includes(id);
    },
    toggleSelect(id) {
      this.selectedIds = this.isSelected(id)
        ? this.selectedIds.filter((x) => x !== id)
        : [...this.selectedIds, id];
    },
    allSelected() {
      return this.resources.length > 0 && this.selectedIds.length === this.resources.length;
    },
    toggleSelectAll() {
      this.selectedIds = this.allSelected() ? [] : this.resources.map((r) => r.id);
    },
    async bulkDelete() {
      const n = this.selectedIds.length;
      if (n === 0) return;
      if (!confirm(t("resources.confirm_bulk_delete", { n }))) return;
      this.bulkDeleting = true;
      try {
        const res = await fetch("/api/v1/resources/bulk-delete", {
          method: "POST", headers: { "content-type": "application/json" },
          body: JSON.stringify({ ids: this.selectedIds }),
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        this.selectedIds = [];
        await this.loadResources();
      } catch (e) {
        this.error = t("resources.error_delete", { error: e.message });
      } finally {
        this.bulkDeleting = false;
      }
    },
    openPortForm(resourceId) {
      this.portFormFor = resourceId;
      this.portForm = { allPorts: false, port: "", portEnd: "", transport: "tcp", protocol: "", label: "", pathPrefix: "",
        maxConcurrentUsers: "", maxReservationMinutes: "", autoApproveReservations: true };
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
            this.portError = t("resources.port_range_invalid");
            return;
          }
          portEnd = this.portForm.portEnd ? parseInt(this.portForm.portEnd, 10) : null;
          if (portEnd !== null && (isNaN(portEnd) || portEnd <= portNum || portEnd > 65535)) {
            this.portError = t("resources.port_end_invalid");
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
            pathPrefix: this.portForm.pathPrefix || null,
            // Blank capacity fields go over the wire as null ("unlimited" /
            // "no ceiling"), not as 0 or "" — the server distinguishes absent
            // from zero and the entity treats null as "not reservable".
            maxConcurrentUsers: this.portForm.maxConcurrentUsers === "" || this.portForm.maxConcurrentUsers == null
                ? null : Number(this.portForm.maxConcurrentUsers),
            maxReservationMinutes: this.portForm.maxReservationMinutes === "" || this.portForm.maxReservationMinutes == null
                ? null : Number(this.portForm.maxReservationMinutes),
            autoApproveReservations: this.portForm.autoApproveReservations,
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
    sortBy(key) {
      if (this.sortKey === key) this.sortDir *= -1;
      else { this.sortKey = key; this.sortDir = 1; }
    },
    sortIcon(key) {
      if (this.sortKey !== key) return "↕";
      return this.sortDir === 1 ? "↑" : "↓";
    },

    // -- Device discovery (ADR-0014) --------------------------------------
    openScan() {
      this.scanOpen = true;
      this.scanState = "consent";
      this.scanHosts = [];
      this.scanJobId = null;
      this.scanError = null;
      this.scanCanForce = false;
      this.scanFound = 0;
    },
    closeScan() {
      if (this.scanPollTimer) { clearTimeout(this.scanPollTimer); this.scanPollTimer = null; }
      // Best-effort cancel a scan still running on the hub.
      if (this.scanJobId && this.scanState === "running") {
        fetch("/api/v1/sites/" + this.siteId + "/discovery/scan/" + this.scanJobId, { method: "DELETE" }).catch(() => {});
      }
      this.scanOpen = false;
      this.scanState = null;
      this.scanJobId = null;
    },
    async startScan(force) {
      this.scanState = "running";
      this.scanError = null;
      this.scanCanForce = false;
      this.scanProgress = { done: 0, total: 0 };
      try {
        const url = "/api/v1/sites/" + this.siteId + "/discovery/scan" + (force ? "?force=true" : "");
        const res = await fetch(url, { method: "POST", headers: { "content-type": "application/json" } });
        if (!res.ok) {
          const body = await res.text();
          // The gateway-handshake precondition is the one 409 an admin can deliberately
          // override — e.g. pre-configuring a site while enforcement is degraded (Docker
          // socket proxy not yet wired up), ahead of a native-instance rollout, where the
          // handshake timestamp is meaningless and they want to check reachability directly.
          if (res.status === 409 && !force && body.includes("not connected")) {
            this.scanCanForce = true;
          }
          throw new Error(body || "HTTP " + res.status);
        }
        const jobId = (await res.json()).jobId;
        if (!jobId) throw new Error("scan response contained no jobId");
        this.scanJobId = jobId;
        this.pollScan();
      } catch (e) {
        this.scanState = "error";
        this.scanError = t("discovery.scan_error", { error: e.message });
      }
    },
    async pollScan() {
      try {
        const res = await fetch("/api/v1/sites/" + this.siteId + "/discovery/scan/" + this.scanJobId);
        if (!res.ok) throw new Error("HTTP " + res.status);
        const s = await res.json();
        this.scanProgress = { done: s.done, total: s.total };
        this.scanFound = s.found || 0;
        if (s.state === "running") {
          this.scanPollTimer = setTimeout(() => this.pollScan(), 400);
          return;
        }
        if (s.state === "done") {
          this.scanHosts = s.hosts.map((h) => {
            const name = this.suggestName(h);
            return {
              ...h,
              _selected: !h.alreadyRegistered,
              _name: name,
              _dnsName: this.slugifyDnsName(name),
              _type: (h.typeGuess && h.typeGuess !== "unknown") ? h.typeGuess : "computer",
            };
          });
          this.scanState = "done";
        } else {
          this.scanState = "error";
          this.scanError = s.error || t("discovery.failed");
        }
      } catch (e) {
        this.scanState = "error";
        this.scanError = t("discovery.scan_error", { error: e.message });
      }
    },
    suggestName(h) {
      // Prefer the reverse-DNS name (first label) when the hub could resolve it.
      if (h.hostname) {
        const label = h.hostname.split(".")[0].trim();
        if (label) return label;
      }
      const last = h.ip.split(".").pop();
      const base = (h.typeGuess && h.typeGuess !== "unknown") ? h.typeGuess : "host";
      return base + "-" + last;
    },
    scanSelectedCount() {
      return this.scanHosts.filter((h) => h._selected && !h.alreadyRegistered).length;
    },
    scanNewCount() {
      return this.scanHosts.filter((h) => !h.alreadyRegistered).length;
    },
    scanKnownCount() {
      return this.scanHosts.filter((h) => h.alreadyRegistered).length;
    },
    async importScan() {
      const chosen = this.scanHosts.filter((h) => h._selected && !h.alreadyRegistered);
      if (chosen.length === 0) return;
      this.importing = true;
      this.scanError = null;
      try {
        const res = await fetch("/api/v1/sites/" + this.siteId + "/discovery/import", {
          method: "POST", headers: { "content-type": "application/json" },
          body: JSON.stringify({ hosts: chosen.map((h) => ({
            ip: h.ip, name: h._name, type: h._type, dnsName: h._dnsName || "",
            ports: this.adoptPorts ? (h.openPorts || []) : [],
          })) }),
        });
        if (!res.ok) throw new Error((await res.text()) || "HTTP " + res.status);
        await this.loadResources();
        this.closeScan();
      } catch (e) {
        this.scanError = t("discovery.import_error", { error: e.message });
      } finally {
        this.importing = false;
      }
    },
  },
  template: `
    <div class="page-header">
      <div style="display: flex; align-items: center; gap: var(--space-3)">
        <button class="btn btn-ghost btn-sm" @click="backToSites">← {{ t('resources.back_sites') }}</button>
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
      <div style="display: flex; gap: var(--space-2)">
        <input ref="searchInput" class="input input-sm" type="search" v-model="quickFilter"
               :placeholder="t('resources.quickfilter_ph')" style="width: 180px" />
        <div style="display: inline-flex; border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden">
          <button class="btn btn-sm" :class="viewMode === 'cards' ? 'btn-secondary' : 'btn-ghost'"
                  style="border: none; border-radius: 0" @click="setView('cards')">{{ t('resources.view_cards') }}</button>
          <button class="btn btn-sm" :class="viewMode === 'list' ? 'btn-secondary' : 'btn-ghost'"
                  style="border: none; border-radius: 0" @click="setView('list')">{{ t('resources.view_list') }}</button>
        </div>
        <button v-if="site" class="btn btn-secondary btn-sm" @click="openScan">
          <Icon name="networks" :size="13" />{{ t('discovery.scan_btn') }}
        </button>
        <button class="btn btn-primary btn-sm" @click="openCreate">{{ t('resources.create_btn') }}</button>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="loading" class="muted">{{ t('common.loading') }}</div>

    <div v-else-if="resources.length === 0" class="empty-state">
      <h2>{{ t('resources.empty_title') }}</h2>
      <p>{{ t('resources.empty_desc') }}</p>
    </div>

    <div v-else-if="filteredResources.length === 0" class="empty-state">
      <h2>{{ t('resources.quickfilter_empty_title') }}</h2>
      <p>{{ t('resources.quickfilter_empty_desc') }}</p>
    </div>

    <!-- List view: multi-select + bulk delete -->
    <div v-else-if="viewMode === 'list'">
      <div style="display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); flex-wrap: wrap; margin-bottom: var(--space-3)">
        <span class="muted" style="font-size: var(--text-sm)">
          {{ selectedIds.length > 0 ? t('resources.n_selected', { n: selectedIds.length }) : t('resources.select_hint') }}
        </span>
        <button class="btn btn-danger btn-sm" :disabled="selectedIds.length === 0 || bulkDeleting" @click="bulkDelete">
          <Icon name="trash" :size="13" />{{ bulkDeleting ? t('common.loading') : t('resources.btn_delete_selected', { n: selectedIds.length }) }}
        </button>
      </div>
      <div style="overflow-x: auto">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 32px"><input type="checkbox" :checked="allSelected()" @change="toggleSelectAll" /></th>
              <th @click="sortBy('name')" style="cursor: pointer; user-select: none; white-space: nowrap">
                {{ t('resources.th_name') }} <span class="muted" style="font-size: 10px">{{ sortIcon('name') }}</span>
              </th>
              <th @click="sortBy('ip')" style="cursor: pointer; user-select: none; white-space: nowrap">
                {{ t('discovery.th_ip') }} <span class="muted" style="font-size: 10px">{{ sortIcon('ip') }}</span>
              </th>
              <th @click="sortBy('type')" style="cursor: pointer; user-select: none; white-space: nowrap">
                {{ t('discovery.th_type') }} <span class="muted" style="font-size: 10px">{{ sortIcon('type') }}</span>
              </th>
              <th>Ports</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in sortedResources" :key="r.id" @click="toggleSelect(r.id)" style="cursor: pointer"
                :style="isSelected(r.id) ? 'background: var(--surface-2)' : ''">
              <td><input type="checkbox" :checked="isSelected(r.id)" @click.stop="toggleSelect(r.id)" /></td>
              <td>{{ r.name }}</td>
              <td class="mono" style="font-size: var(--text-xs)">{{ r.ip }}</td>
              <td>{{ typeLabels[r.type] || r.type || '—' }}</td>
              <td class="mono" style="font-size: var(--text-xs)">{{ (r.ports || []).length }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-else class="res-grid">
      <div v-for="r in filteredResources" :key="r.id" class="res-card">

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
                      ::title="portGroups.length === 0 ? t('resources.group_needed') : ''"
                      @click="groupFormFor === r.id ? closeGroupForm() : openGroupForm(r.id)">
                {{ groupFormFor === r.id ? '✕ ' + t('common.cancel') : '+ ' + t('resources.from_group') }}
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
                    <option>PRINT</option>
                    <option>X11</option>
                    <option>POSTGRES</option>
                    <option>MYSQL</option>
                    <option>MARIADB</option>
                    <option>KAFKA</option>
                    <option>NATS</option>
                    <option>EMS</option>
                    <option>HOMEASSISTANT</option>
                    <option>IOBROKER</option>
                    <option>CUSTOM</option>
                  </select>
                </div>
                <div class="field" style="margin: 0; flex: 1">
                  <label>Label (optional)</label>
                  <input class="input" v-model="portForm.label" :placeholder="t('resources.port_label_ph')" />
                </div>
                <div v-if="portForm.protocol === 'HTTP' || portForm.protocol === 'HTTPS'" class="field" style="margin: 0; flex: 1">
                  <label>{{ t('resources.label_path_prefix') }}</label>
                  <input class="input mono" v-model="portForm.pathPrefix" placeholder="/admin" />
                </div>
                <div style="display: flex; gap: var(--space-2); align-self: flex-end">
                  <button type="submit" class="btn btn-primary btn-sm">{{ t('resources.add_btn') }}</button>
                </div>
              </div>
              <!-- Exclusive capacity (#72). Left empty, the port behaves
                   exactly as before: a grant alone reaches it. -->
              <div style="display: flex; gap: var(--space-3); flex-wrap: wrap; align-items: flex-start; margin-top: var(--space-3)">
                <div class="field" style="margin: 0; flex: 1; min-width: 180px">
                  <label for="portMaxUsers">{{ t('resources.field_capacity') }} <span style="color:var(--fg3); font-weight:400">(optional)</span></label>
                  <input id="portMaxUsers" class="input mono" type="number" min="1" step="1"
                         v-model="portForm.maxConcurrentUsers"
                         :placeholder="t('resources.field_capacity_ph')" />
                </div>
                <div v-if="portForm.maxConcurrentUsers" class="field" style="margin: 0; flex: 1; min-width: 180px">
                  <label for="portMaxRsv">{{ t('resources.field_max_reservation') }} <span style="color:var(--fg3); font-weight:400">(optional)</span></label>
                  <input id="portMaxRsv" class="input mono" type="number" min="5" step="5"
                         v-model="portForm.maxReservationMinutes"
                         :placeholder="t('resources.field_max_reservation_ph')" />
                </div>
              </div>
              <div class="field-hint" style="margin-top: var(--space-1)">{{ t('resources.field_capacity_hint') }}</div>
              <template v-if="portForm.maxConcurrentUsers">
                <label style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; user-select: none; margin-top: var(--space-2); font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500; text-transform: none; letter-spacing: 0">
                  <input type="checkbox" v-model="portForm.autoApproveReservations" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" />
                  <span>{{ t('resources.field_auto_approve_label') }}</span>
                </label>
                <div class="field-hint" style="margin-top: var(--space-1)">{{ t('resources.field_auto_approve_hint') }}</div>
              </template>
              <div v-if="portForm.protocol === 'RDP' && !ironRdpEnabled" class="callout callout-info" style="margin-top: var(--space-3)">
                {{ t('resources.iron_rdp_disabled_hint') }}
                <router-link :to="{ name: 'settings' }">{{ t('resources.iron_rdp_disabled_link') }}</router-link>
              </div>
              <div v-if="portError" class="error-banner" style="margin-top: var(--space-3)">{{ portError }}</div>
            </form>
          </div>

          <!-- Group apply form -->
          <div v-if="groupFormFor === r.id" class="res-inline-form">
            <form @submit.prevent="applyGroup">
              <div class="res-form-row">
                <div class="field" style="margin: 0; min-width: 220px">
                  <label>{{ t('resources.port_group') }}</label>
                  <select class="select" v-model="selectedGroupId" required>
                    <option v-for="g in portGroups" :key="g.id" :value="g.id">{{ g.name }}</option>
                  </select>
                </div>
                <div style="flex: 1; align-self: flex-end; font-size: var(--text-xs); color: var(--fg3); font-family: var(--font-sans); padding-bottom: 8px">
                  <span v-if="selectedGroupMembers().length === 0">{{ t('resources.group_empty') }}</span>
                  <span v-else>
                    {{ t('resources.group_adds') }}
                    <span v-for="(m, i) in selectedGroupMembers()" :key="m.id">
                      <span class="mono">{{ m.port === 0 ? t('resources.port_all') : (m.portEnd ? m.port + '–' + m.portEnd : m.port) }}/{{ m.transport }}</span>{{ i < selectedGroupMembers().length - 1 ? ', ' : '' }}
                    </span>
                  </span>
                </div>
                <div style="align-self: flex-end">
                  <button type="submit" class="btn btn-primary btn-sm" :disabled="!selectedGroupId || selectedGroupMembers().length === 0">{{ t('resources.btn_apply') }}</button>
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
                  {v:'rackserver', l: t('resources.type_rackserver')},
                  {v:'kvm',        l: t('resources.type_kvm')},
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

            <div class="field" style="margin: var(--space-4) 0 0">
              <label for="resDnsName">{{ t('resources.field_dns_name') }} <span style="color:var(--fg3); font-weight:400">(optional)</span></label>
              <input id="resDnsName" class="input mono" v-model="form.dnsName" :placeholder="dnsNameSuggestion || t('resources.field_dns_name_ph')" />
              <div v-if="dnsNameSuggestion" class="field-hint" style="display:flex; align-items:center; gap: var(--space-2)">
                <span>{{ t('resources.field_dns_name_suggestion', { name: dnsNameSuggestion }) }}</span>
                <button type="button" class="btn btn-ghost btn-sm" style="padding: 0 var(--space-2); height: auto; min-height: 0; line-height: 1.6"
                        @click="acceptDnsNameSuggestion">{{ t('resources.field_dns_name_accept') }}</button>
              </div>
              <div class="field-hint">{{ t('resources.field_dns_name_hint') }}</div>
              <label v-if="form.dnsName" style="display: inline-flex; align-items: center; gap: var(--space-2); cursor: pointer; user-select: none; margin-top: var(--space-2); font-family: var(--font-sans); font-size: var(--text-sm); color: var(--fg1); font-weight: 500; text-transform: none; letter-spacing: 0">
                <input type="checkbox" v-model="form.dnsFlat" style="width: 16px; height: 16px; accent-color: var(--accent); margin: 0" />
                <span>{{ t('resources.field_dns_flat_label') }}</span>
              </label>
              <div v-if="form.dnsName" class="field-hint" style="margin-top: var(--space-1)">{{ t('resources.field_dns_flat_hint') }}</div>
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

    <!-- Device discovery scan (ADR-0014) -->
    <div v-if="scanOpen" class="modal-backdrop" @click.self="closeScan">
      <div class="modal modal-lg">
        <div class="modal-header">
          <h2>{{ t('discovery.title') }}</h2>
          <button class="btn btn-ghost btn-sm" @click="closeScan">✕</button>
        </div>
        <div class="modal-body">
          <template v-if="scanState === 'consent'">
            <p style="font-size: var(--text-sm); color: var(--fg1); margin-bottom: var(--space-2)">
              {{ t('discovery.consent', { cidr: site ? site.cidr : '' }) }}
            </p>
            <p class="field-hint" style="margin: 0">{{ t('discovery.consent_hint') }}</p>
          </template>

          <template v-else-if="scanState === 'running'">
            <div class="progress" role="progressbar" :aria-valuenow="scanProgress.done" :aria-valuemin="0" :aria-valuemax="scanProgress.total">
              <div class="progress-fill" :style="{ width: scanProgressPct + '%' }"></div>
            </div>
            <div style="display: flex; justify-content: space-between; align-items: baseline; gap: var(--space-2); margin-top: var(--space-2)">
              <p class="muted" style="margin: 0">{{ t('discovery.running_hint') }}</p>
              <p class="mono" style="margin: 0; font-size: var(--text-sm); color: var(--fg1); white-space: nowrap">{{ t('discovery.running', { done: scanProgress.done, total: scanProgress.total }) }} · {{ t('discovery.found', { n: scanFound }) }}</p>
            </div>
          </template>

          <template v-else-if="scanState === 'error'">
            <div class="error-banner">{{ scanError }}</div>
            <p v-if="scanCanForce" class="field-hint" style="margin: var(--space-2) 0 0">{{ t('discovery.force_hint') }}</p>
          </template>

          <template v-else-if="scanState === 'done'">
            <div v-if="scanError" class="error-banner" style="margin-bottom: var(--space-3)">{{ scanError }}</div>
            <div v-if="scanHosts.length === 0" class="muted">{{ t('discovery.none') }}</div>
            <template v-else>
              <div style="display: flex; justify-content: space-between; align-items: center; gap: var(--space-3); flex-wrap: wrap; margin-bottom: var(--space-3)">
                <p class="field-hint" style="margin: 0">{{ t('discovery.summary', { new: scanNewCount(), known: scanKnownCount() }) }}</p>
                <label style="display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-sm); color: var(--fg1)">
                  <input type="checkbox" v-model="adoptPorts" />
                  {{ t('discovery.adopt_ports') }}
                </label>
              </div>
            <div style="overflow-x: auto">
              <table class="table">
                <thead>
                  <tr>
                    <th style="width: 32px"></th>
                    <th>{{ t('discovery.th_ip') }}</th>
                    <th>{{ t('discovery.th_ports') }}</th>
                    <th>{{ t('discovery.th_type') }}</th>
                    <th>{{ t('discovery.th_name') }}</th>
                    <th>{{ t('resources.field_dns_name') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="h in scanHosts" :key="h.ip" :style="h.alreadyRegistered ? 'opacity: 0.5' : ''">
                    <td><input type="checkbox" v-model="h._selected" :disabled="h.alreadyRegistered" /></td>
                    <td class="mono">
                      {{ h.ip }}
                      <span v-if="h.alreadyRegistered" class="muted" style="font-size: var(--text-xs)"> · {{ t('discovery.registered') }}</span>
                      <div v-if="h.hostname" class="muted" style="font-size: var(--text-xs)">{{ h.hostname }}</div>
                    </td>
                    <td class="mono" style="font-size: var(--text-xs)">{{ h.openPorts.length ? h.openPorts.join(', ') : '—' }}</td>
                    <td>
                      <select class="select" v-model="h._type" :disabled="h.alreadyRegistered" style="width: 130px">
                        <option v-for="(label, val) in typeLabels" :key="val" :value="val">{{ label }}</option>
                      </select>
                    </td>
                    <td><input class="input" v-model="h._name" :disabled="h.alreadyRegistered" style="width: 170px" /></td>
                    <td><input class="input mono" v-model="h._dnsName" :disabled="h.alreadyRegistered" style="width: 150px" :placeholder="t('resources.field_dns_name_ph')" /></td>
                  </tr>
                </tbody>
              </table>
            </div>
            </template>
          </template>
        </div>
        <div class="modal-footer">
          <button v-if="scanState !== 'running'" type="button" class="btn btn-ghost" @click="closeScan">{{ t('common.cancel') }}</button>
          <button v-else type="button" class="btn btn-secondary" @click="closeScan">{{ t('discovery.abort_btn') }}</button>
          <button v-if="scanState === 'consent'" type="button" class="btn btn-primary" @click="startScan()">{{ t('discovery.start_btn') }}</button>
          <button v-if="scanState === 'error' && scanCanForce" type="button" class="btn btn-primary" @click="startScan(true)">
            {{ t('discovery.force_btn') }}
          </button>
          <button v-else-if="scanState === 'done' && scanHosts.length > 0" type="button" class="btn btn-primary"
                  :disabled="importing || scanSelectedCount() === 0" @click="importScan">
            {{ importing ? t('discovery.importing') : t('discovery.import_btn', { n: scanSelectedCount() }) }}
          </button>
        </div>
      </div>
    </div>
  `,
});
